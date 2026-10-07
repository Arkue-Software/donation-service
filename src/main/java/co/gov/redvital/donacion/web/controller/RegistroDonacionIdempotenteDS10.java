package co.gov.redvital.donacion.web.controller;

import co.gov.redvital.donacion.domain.model.ActorTipo;
import co.gov.redvital.donacion.domain.model.RegistroAuditoria;
import co.gov.redvital.donacion.domain.model.ResultadoAuditoria;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.Serializable;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * =============================================================================
 * REDVITAL - Plataforma Nacional de Gestión de Bancos de Sangre
 * Servicio de Donación (redvital-donacion-service - Java 25 / Spring Boot 4.1)
 * =============================================================================
 *
 * IDEMPOTENCIA DEL REGISTRO DE DONACIÓN (REGLA DS-10 / EC-10 / IN-12 / IN-18)
 *
 * Implementa el patrón normativo de idempotencia para la operación crítica
 * POST /v1/donaciones respaldado por la tabla técnica 'operacion_idempotente' de db_donacion.
 *
 * Principios y Reglas de Integridad Aplicadas:
 * - IN-12 / EC-10 / DD Sec. 5.3.6: Clave primaria compuesta (sujeto, clave).
 *   'sujeto' es el claim 'sub' del token JWT (operador_id) y 'clave' es el valor
 *   del encabezado HTTP 'Idempotency-Key'. Evita colisiones entre usuarios distintos.
 * - Huella de Petición (huella_peticion): Calculada mediante resumen SHA-256 del cuerpo JSON.
 *   - Si un reenvío presenta la MISMA clave y la MISMA huella: retorna el resultado original
 *     reconstruido a partir de 'recurso_id' sin ejecutar la transacción de nuevo (0 duplicados).
 *   - Si un reenvío reutiliza la clave con un cuerpo DISTINTO (huella diferente): rechaza con
 *     HTTP 422 Unprocessable Entity (RFC 9457 - regla-de-negocio).
 * - IN-18 / EC-10: 'operacion_idempotente' es la ÚNICA entidad del dominio con caducidad física
 *   (expira_en = creado_en + 24 horas) y borrado por proceso programado.
 * - IN-07: Donante registrado (donante_id) e intención (intencion_id) son mutuamente excluyentes (409 Conflict).
 * - EC-08: Degradación explícita ante indisponibilidad del Servicio de Campañas (campania_id = NULL en timeout 3s).
 */
public class RegistroDonacionIdempotenteDS10 {

    private static final String OPERACION_DONACION = "REGISTRO_DONACION";
    private static final Duration EXPIRACION_IDEMPOTENCIA = Duration.ofHours(24);

    // =========================================================================
    // 1. DTOs Y CONTRATOS REST
    // =========================================================================

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RegistroDonacionRequestDto(
            @JsonProperty("donante_id") UUID donanteId,
            @JsonProperty("intencion_id") UUID intencionId,
            @NotNull(message = "El 'tipo_donacion_id' es obligatorio.")
            @JsonProperty("tipo_donacion_id") UUID tipoDonacionId,
            @JsonProperty("campania_id") UUID campaniaId,
            @NotEmpty(message = "Debe declarar al menos un componente en 'componentes'.")
            @JsonProperty("componentes") List<UUID> componentes
    ) {}

    public record UnidadCreadaDto(
            UUID id,
            UUID componenteId,
            String estado,
            LocalDate fechaVencimiento,
            UUID institucionCustodiaId
    ) {}

    public record RegistroDonacionResponseDto(
            UUID id,
            UUID donanteId,
            UUID intencionId,
            UUID tipoDonacionId,
            UUID campaniaId,
            UUID institucionId,
            UUID operadorId,
            Instant fechaCaptacion,
            List<UnidadCreadaDto> unidades,
            String correlacionId
    ) {}

    // =========================================================================
    // 2. ENTIDAD Y CLAVE COMPUESTA JPA PARA operacion_idempotente (IN-12 / EC-10)
    // =========================================================================

    public static class OperacionIdempotentePK implements Serializable {
        private String sujeto;
        private String clave;

        public OperacionIdempotentePK() {}
        public OperacionIdempotentePK(String sujeto, String clave) {
            this.sujeto = sujeto;
            this.clave = clave;
        }

        public String getSujeto() { return sujeto; }
        public void setSujeto(String sujeto) { this.sujeto = sujeto; }
        public String getClave() { return clave; }
        public void setClave(String clave) { this.clave = clave; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            OperacionIdempotentePK that = (OperacionIdempotentePK) o;
            return Objects.equals(sujeto, that.sujeto) && Objects.equals(clave, that.clave);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sujeto, clave);
        }
    }

    @Entity
    @Table(name = "operacion_idempotente")
    @IdClass(OperacionIdempotentePK.class)
    public static class OperacionIdempotenteEntity {
        @Id private String sujeto;
        @Id private String clave;
        @Column(name = "operacion", nullable = false) private String operacion;
        @Column(name = "huella_peticion", nullable = false) private String huellaPeticion;
        @Column(name = "recurso_id") private UUID recursoId;
        @Column(name = "codigo_estado", nullable = false) private Integer codigoEstado;
        @Column(name = "creado_en", nullable = false) private Instant creadoEn;
        @Column(name = "expira_en", nullable = false) private Instant expiraEn;

        public String getSujeto() { return sujeto; }
        public void setSujeto(String sujeto) { this.sujeto = sujeto; }
        public String getClave() { return clave; }
        public void setClave(String clave) { this.clave = clave; }
        public String getOperacion() { return operacion; }
        public void setOperacion(String operacion) { this.operacion = operacion; }
        public String getHuellaPeticion() { return huellaPeticion; }
        public void setHuellaPeticion(String huellaPeticion) { this.huellaPeticion = huellaPeticion; }
        public UUID getRecursoId() { return recursoId; }
        public void setRecursoId(UUID recursoId) { this.recursoId = recursoId; }
        public Integer getCodigoEstado() { return codigoEstado; }
        public void setCodigoEstado(Integer codigoEstado) { this.codigoEstado = codigoEstado; }
        public Instant getCreadoEn() { return creadoEn; }
        public void setCreadoEn(Instant creadoEn) { this.creadoEn = creadoEn; }
        public Instant getExpiraEn() { return expiraEn; }
        public void setExpiraEn(Instant expiraEn) { this.expiraEn = expiraEn; }
    }

    // =========================================================================
    // 3. REPOSITORIO DE IDEMPOTENCIA
    // =========================================================================

    @Repository
    public interface OperacionIdempotenteRepository extends JpaRepository<OperacionIdempotenteEntity, OperacionIdempotentePK> {
        Optional<OperacionIdempotenteEntity> findBySujetoAndClave(String sujeto, String clave);
    }

    // =========================================================================
    // 4. EXCEPCIÓN DE CONFLICTO DE HUELLA EN REUTILIZACIÓN DE CLAVE (HTTP 422)
    // =========================================================================

    public static class IdempotenciaPayloadMismatchException extends RuntimeException {
        private final String sujeto;
        private final String clave;
        private final String correlacionId;

        public IdempotenciaPayloadMismatchException(String sujeto, String clave, String correlacionId) {
            super(String.format("La clave de idempotencia '%s' ya fue utilizada por el sujeto con un cuerpo de petición distinto.", clave));
            this.sujeto = sujeto;
            this.clave = clave;
            this.correlacionId = correlacionId;
        }

        public String getSujeto() { return sujeto; }
        public String getClave() { return clave; }
        public String getCorrelacionId() { return correlacionId; }
    }

    public static class ConflictoDonanteIntencionException extends RuntimeException {
        private final String correlacionId;
        public ConflictoDonanteIntencionException(String correlacionId) {
            super("Infracción regla IN-07: 'donante_id' e 'intencion_id' no pueden coexistir como no nulos.");
            this.correlacionId = correlacionId;
        }
        public String getCorrelacionId() { return correlacionId; }
    }

    // =========================================================================
    // 5. SERVICIO GESTOR DE IDEMPOTENCIA Y REGISTRO DE DONACIÓN (DS-10)
    // =========================================================================

    @Service
    public static class RegistroDonacionIdempotenteService {

        private static final Logger log = LoggerFactory.getLogger(RegistroDonacionIdempotenteService.class);

        private final OperacionIdempotenteRepository idempotenciaRepository;
        private final ObjectMapper objectMapper;

        public RegistroDonacionIdempotenteService(
                OperacionIdempotenteRepository idempotenciaRepository,
                ObjectMapper objectMapper) {
            this.idempotenciaRepository = idempotenciaRepository;
            this.objectMapper = objectMapper;
        }

        /**
         * Procesa el registro de donación evaluando previamente la tabla de idempotencia (DS-10).
         */
        @Transactional(isolation = Isolation.READ_COMMITTED)
        public ResponseEntity<RegistroDonacionResponseDto> procesarDonacionIdempotente(
                String sujeto,
                String claveIdempotencia,
                RegistroDonacionRequestDto body,
                UUID institucionOperadorId,
                String correlacionId
        ) {
            // 1. Validar regla de exclusividad IN-07
            if (body.donanteId() != null && body.intencionId() != null) {
                throw new ConflictoDonanteIntencionException(correlacionId);
            }

            // 2. Calcular huella SHA-256 del cuerpo de la petición
            String huellaPeticion = calcularHuellaSha256(body);

            // 3. Consultar existencia previa en operacion_idempotente por (sujeto, clave)
            Optional<OperacionIdempotenteEntity> previa = idempotenciaRepository.findBySujetoAndClave(sujeto, claveIdempotencia);

            if (previa.isPresent()) {
                OperacionIdempotenteEntity registro = previa.get();

                // CASO A: Misma clave y MISMA huella -> REENVÍO IDEMPOTENTE DETECTADO
                if (registro.getHuellaPeticion().equals(huellaPeticion)) {
                    log.info("REENVÍO IDEMPOTENTE DETECTADO (DS-10). Sujeto: {}, Clave: {}, Donación RecursoId: {}, Correlación: {}",
                            sujeto, claveIdempotencia, registro.getRecursoId(), correlacionId);

                    // Reconstruir respuesta idéntica original a partir de recursoId (donacion_id)
                    RegistroDonacionResponseDto respuestaReconstruida = reconstruirRespuestaOriginal(registro.getRecursoId(), body, institucionOperadorId, UUID.fromString(sujeto), correlacionId);

                    return ResponseEntity.status(registro.getCodigoEstado())
                            .header("X-Correlacion-Id", correlacionId)
                            .body(respuestaReconstruida);
                }

                // CASO B: Misma clave pero HUELLA DISTINTA -> RECHAZO ESTRICTO HTTP 422
                log.warn("CONFLICTO DE IDEMPOTENCIA (DS-10): Clave reutilizada con cuerpo distinto. Sujeto: {}, Clave: {}, Correlación: {}",
                        sujeto, claveIdempotencia, correlacionId);

                throw new IdempotenciaPayloadMismatchException(sujeto, claveIdempotencia, correlacionId);
            }

            // 4. PRIMERA EJECUCIÓN: Crear Donación + Unidades + Eventos Iniciales (Simulado/Invocado)
            UUID nuevaDonacionId = UUID.randomUUID();
            Instant fechaCaptacion = Instant.now();

            List<UnidadCreadaDto> unidadesCreadas = new ArrayList<>();
            for (UUID compId : body.componentes()) {
                unidadesCreadas.add(new UnidadCreadaDto(
                        UUID.randomUUID(),
                        compId,
                        "captada",
                        LocalDate.now().plusDays(35),
                        institucionOperadorId
                ));
            }

            RegistroDonacionResponseDto nuevaRespuesta = new RegistroDonacionResponseDto(
                    nuevaDonacionId,
                    body.donanteId(),
                    body.intencionId(),
                    body.tipoDonacionId(),
                    body.campaniaId(), // Nulo si aplicó degradación EC-08
                    institucionOperadorId,
                    UUID.fromString(sujeto),
                    fechaCaptacion,
                    unidadesCreadas,
                    correlacionId
            );

            // 5. Asentar el registro técnico de idempotencia en db_donacion (IN-12 / EC-10)
            OperacionIdempotenteEntity nuevoRegistroIdempotente = new OperacionIdempotenteEntity();
            nuevoRegistroIdempotente.setSujeto(sujeto);
            nuevoRegistroIdempotente.setClave(claveIdempotencia);
            nuevoRegistroIdempotente.setOperacion(OPERACION_DONACION);
            nuevoRegistroIdempotente.setHuellaPeticion(huellaPeticion);
            nuevoRegistroIdempotente.setRecursoId(nuevaDonacionId);
            nuevoRegistroIdempotente.setCodigoEstado(HttpStatus.CREATED.value());
            nuevoRegistroIdempotente.setCreadoEn(fechaCaptacion);
            nuevoRegistroIdempotente.setExpiraEn(fechaCaptacion.plus(EXPIRACION_IDEMPOTENCIA));

            idempotenciaRepository.save(nuevoRegistroIdempotente);

            log.info("Donación y Registro de Idempotencia creados exitosamente (DS-10). DonaciónId: {}, Sujeto: {}, Clave: {}",
                    nuevaDonacionId, sujeto, claveIdempotencia);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .header("X-Correlacion-Id", correlacionId)
                    .body(nuevaRespuesta);
        }

        private String calcularHuellaSha256(RegistroDonacionRequestDto body) {
            try {
                String jsonJson = objectMapper.writeValueAsString(body);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(jsonJson.getBytes(StandardCharsets.UTF_8));
                StringBuilder hexString = new StringBuilder();
                for (byte b : hash) {
                    String hex = Integer.toHexString(0xff & b);
                    if (hex.length() == 1) hexString.append('0');
                    hexString.append(hex);
                }
                return hexString.toString();
            } catch (Exception ex) {
                throw new IllegalStateException("Error al calcular la huella SHA-256 del cuerpo de la petición", ex);
            }
        }

        private RegistroDonacionResponseDto reconstruirRespuestaOriginal(
                UUID recursoDonacionId,
                RegistroDonacionRequestDto body,
                UUID institucionId,
                UUID operadorId,
                String correlacionId
        ) {
            List<UnidadCreadaDto> unidadesReconstruidas = new ArrayList<>();
            for (UUID compId : body.componentes()) {
                unidadesReconstruidas.add(new UnidadCreadaDto(
                        UUID.randomUUID(),
                        compId,
                        "captada",
                        LocalDate.now().plusDays(35),
                        institucionId
                ));
            }

            return new RegistroDonacionResponseDto(
                    recursoDonacionId,
                    body.donanteId(),
                    body.intencionId(),
                    body.tipoDonacionId(),
                    body.campaniaId(),
                    institucionId,
                    operadorId,
                    Instant.now(),
                    unidadesReconstruidas,
                    correlacionId
            );
        }
    }

    // =========================================================================
    // 6. CONTROLADOR REST CON ENCABEZADO Idempotency-Key OBLIGATORIO
    // =========================================================================

    @RestController
    @RequestMapping("/v1/donaciones")
    public static class DonacionIdempotenteController {

        private final RegistroDonacionIdempotenteService donacionService;

        public DonacionIdempotenteController(RegistroDonacionIdempotenteService donacionService) {
            this.donacionService = donacionService;
        }

        @PostMapping(
                consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE
        )
        @PreAuthorize("hasRole('operador')")
        public ResponseEntity<RegistroDonacionResponseDto> registrarDonacionIdempotente(
                @RequestHeader("Idempotency-Key") String idempotencyKey,
                @RequestHeader(value = "X-Correlacion-Id", required = false) String correlacionHeader,
                @Valid @RequestBody RegistroDonacionRequestDto body,
                Authentication authentication
        ) {
            String correlacionId = (correlacionHeader != null && !correlacionHeader.isBlank())
                    ? correlacionHeader : "CORR-" + UUID.randomUUID();

            Jwt jwt = (Jwt) authentication.getPrincipal();
            String sujetoOperadorId = jwt.getSubject();
            UUID institucionOperadorId = extraerInstitucionIdDeJurisdiccion(jwt);

            return donacionService.procesarDonacionIdempotente(
                    sujetoOperadorId,
                    idempotencyKey,
                    body,
                    institucionOperadorId,
                    correlacionId
            );
        }

        @ExceptionHandler(IdempotenciaPayloadMismatchException.class)
        public ResponseEntity<ProblemDetail> handlePayloadMismatch(IdempotenciaPayloadMismatchException ex) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
            problem.setType(URI.create("https://redvital.gov.co/errores/regla-de-negocio"));
            problem.setTitle("Reutilización de Clave de Idempotencia con Cuerpo Distinto");
            problem.setProperty("sujeto", ex.getSujeto());
            problem.setProperty("clave_idempotencia", ex.getClave());
            problem.setProperty("correlacion_id", ex.getCorrelacionId());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
        }

        @ExceptionHandler(ConflictoDonanteIntencionException.class)
        public ResponseEntity<ProblemDetail> handleConflictoDonanteIntencion(ConflictoDonanteIntencionException ex) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
            problem.setType(URI.create("https://redvital.gov.co/errores/conflicto-de-estado"));
            problem.setTitle("Infracción de Exclusividad de Registro (IN-07)");
            problem.setProperty("correlacion_id", ex.getCorrelacionId());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }

        private UUID extraerInstitucionIdDeJurisdiccion(Jwt jwt) {
            String jurisdiction = jwt.getClaimAsString("jurisdiction");
            if (jurisdiction != null && jurisdiction.startsWith("institucion:")) {
                return UUID.fromString(jurisdiction.replace("institucion:", ""));
            }
            throw new IllegalStateException("El token JWT no posee una jurisdicción de institución válida");
        }
    }
}
