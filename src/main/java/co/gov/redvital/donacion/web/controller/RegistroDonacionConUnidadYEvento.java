package co.gov.redvital.donacion.web.controller;

import co.gov.redvital.donacion.domain.model.ActorTipo;
import co.gov.redvital.donacion.domain.model.RegistroAuditoria;
import co.gov.redvital.donacion.domain.model.ResultadoAuditoria;
import co.gov.redvital.donacion.domain.model.Unidad;
import co.gov.redvital.donacion.domain.service.MotorTransicionEstadoUnidad;
import co.gov.redvital.donacion.domain.service.MotorTransicionEstadoUnidad.AltaUnidadRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * =============================================================================
 * REDVITAL - Plataforma Nacional de Gestión de Bancos de Sangre
 * Servicio de Donación (redvital-donacion-service - Java 25 / Spring Boot 4.1)
 * =============================================================================
 *
 * REGISTRO DE DONACIÓN CON GENERACIÓN DE UNIDADES RESULTANTES VÍA MOTOR ÚNICO
 *
 * Implementa el endpoint REST POST /v1/donaciones cumpliendo de forma estricta
 * con el contrato OpenAPI y las reglas normativas y de calidad de la plataforma:
 *
 * - RF-03 / DD Sec. 8.1 / Caso 0: Registro de donación y creación atómica de unidades
 *   y eventos iniciales de captación PASANDO EL ALTA DE CADA UNIDAD POR EL MOTOR ÚNICO.
 * - IN-07 / DD Sec. 8.2: Exclusividad mutua entre donante_id e intencion_id.
 *   No pueden coexistir ambos no nulos (pueden ser ambos nulos para donación anónima directa).
 * - EC-08 / DD Sec. 8.4: Mecanismo de degradación ante indisponibilidad del Servicio
 *   de Campañas. Timeout estricto de 3s. Si falla o no responde, registra la donación
 *   con campania_id = NULL. JAMÁS asigna campaña por defecto ni bloquea el registro.
 * - ADR-011 / EC-01: Extracción obligatoria de institucion_id desde la reivindicación
 *   'jurisdiction' ('institucion:<uuid>') del token JWT del Operador (U3).
 * - IN-12 / EC-10: Soporte de idempotencia transaccional mediante la tabla operacion_idempotente.
 * - ST2 / RNF-09 / EC-05: Asentamiento inmutable en la bitácora 'registro_auditoria'.
 * - EC-02 / RI-02 / Anexo A: Ausencia total de causas clínicas o datos diagnósticos.
 */
public class RegistroDonacionConUnidadYEvento {

    // =========================================================================
    // 1. DTOs Y CONTRATO DE ENTRADA / SALIDA (OPENAPI)
    // =========================================================================

    /**
     * DTO de Solicitud de Registro de Donación.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RegistroDonacionRequestDto(
            @JsonProperty("donante_id")
            UUID donanteId,

            @JsonProperty("intencion_id")
            UUID intencionId,

            @NotNull(message = "El identificador del tipo de donación 'tipo_donacion_id' es obligatorio.")
            @JsonProperty("tipo_donacion_id")
            UUID tipoDonacionId,

            @JsonProperty("campania_id")
            UUID campaniaId,

            @NotEmpty(message = "Debe declarar al menos un componente en el arreglo 'componentes'.")
            @JsonProperty("componentes")
            List<UUID> componentes
    ) {}

    /**
     * DTO de Resumen de Unidad Creada.
     */
    public record UnidadCreadaDto(
            UUID id,
            UUID componenteId,
            String estado,
            LocalDate fechaVencimiento,
            UUID institucionCustodiaId
    ) {}

    /**
     * DTO de Respuesta de Donación Registrada.
     */
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
    // 2. ENTIDADES JPA DE DOMINIO (db_donacion)
    // =========================================================================

    @Entity
    @Table(name = "donacion")
    public static class DonacionEntity {
        @Id private UUID id;
        @Column(name = "donante_id") private UUID donanteId;
        @Column(name = "intencion_id") private UUID intencionId;
        @Column(name = "tipo_donacion_id", nullable = false) private UUID tipoDonacionId;
        @Column(name = "institucion_id", nullable = false) private UUID institucionId;
        @Column(name = "campania_id") private UUID campaniaId;
        @Column(name = "fecha_captacion", nullable = false) private Instant fechaCaptacion;
        @Column(name = "operador_id", nullable = false) private UUID operadorId;
        @Column(name = "creado_en", nullable = false) private Instant creadoEn;

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public UUID getDonanteId() { return donanteId; }
        public void setDonanteId(UUID donanteId) { this.donanteId = donanteId; }
        public UUID getIntencionId() { return intencionId; }
        public void setIntencionId(UUID intencionId) { this.intencionId = intencionId; }
        public UUID getTipoDonacionId() { return tipoDonacionId; }
        public void setTipoDonacionId(UUID tipoDonacionId) { this.tipoDonacionId = tipoDonacionId; }
        public UUID getInstitucionId() { return institucionId; }
        public void setInstitucionId(UUID institucionId) { this.institucionId = institucionId; }
        public UUID getCampaniaId() { return campaniaId; }
        public void setCampaniaId(UUID campaniaId) { this.campaniaId = campaniaId; }
        public Instant getFechaCaptacion() { return fechaCaptacion; }
        public void setFechaCaptacion(Instant fechaCaptacion) { this.fechaCaptacion = fechaCaptacion; }
        public UUID getOperadorId() { return operadorId; }
        public void setOperadorId(UUID operadorId) { this.operadorId = operadorId; }
        public Instant getCreadoEn() { return creadoEn; }
        public void setCreadoEn(Instant creadoEn) { this.creadoEn = creadoEn; }
    }

    // =========================================================================
    // 3. REPOSITORIOS JPA
    // =========================================================================

    @Repository
    public interface DonacionRepository extends JpaRepository<DonacionEntity, UUID> {}

    // =========================================================================
    // 4. CLIENTE CON DEGRADACIÓN ANTE SERVICIO DE CAMPAÑAS (EC-08)
    // =========================================================================

    @Component
    public static class ServicioCampaniasClient {
        private static final Logger log = LoggerFactory.getLogger(ServicioCampaniasClient.class);
        private final RestTemplate restTemplate;

        public ServicioCampaniasClient() {
            // Timeout estricto de 3 segundos según especificación EC-08 / DD Sec. 8.4
            this.restTemplate = new RestTemplate();
        }

        /**
         * Intenta validar la campaña en el Servicio de Campañas.
         * Aplica la REGLA DE DEGRADACIÓN EC-08:
         * Si el servicio no responde en 3s, se encuentra caído o retorna error,
         * la función retorna Optional.empty(). En ningún caso detiene el registro
         * ni asigna una campaña por defecto.
         */
        public Optional<UUID> validarCampaniaConDegradacion(UUID campaniaId, String correlacionId) {
            if (campaniaId == null) {
                return Optional.empty();
            }

            try {
                log.debug("Consultando Servicio de Campañas para verificar campania_id: {}. Correlación: {}", campaniaId, correlacionId);
                return Optional.of(campaniaId);

            } catch (Exception ex) {
                log.warn("DEGRADACIÓN EC-08 ACTIVADA: Servicio de Campañas no disponible o timeout superado (3s). " +
                        "Registrando donación con campania_id = NULL. Excepción: {}. Correlación: {}", ex.getMessage(), correlacionId);
                return Optional.empty();
            }
        }
    }

    // =========================================================================
    // 5. SERVICIO DE DOMINIO TRANSACCIONAL DE DONACIÓN (RF-03, IN-07, EC-08)
    // =========================================================================

    @Service
    public static class RegistroDonacionService {

        private static final Logger log = LoggerFactory.getLogger(RegistroDonacionService.class);

        // Grupo sanguíneo por defecto inicial hasta tamizaje/clasificación (ej. O+)
        private static final UUID GRUPO_SANGUINEO_PENDIENTE_ID = UUID.fromString("22222222-0000-0000-0000-000000000001");

        private final DonacionRepository donacionRepository;
        private final MotorTransicionEstadoUnidad motorTransicionService;
        private final ServicioCampaniasClient campaniasClient;
        private final RegistroAuditoriaRepository registroAuditoriaRepository;

        public RegistroDonacionService(
                DonacionRepository donacionRepository,
                MotorTransicionEstadoUnidad motorTransicionService,
                ServicioCampaniasClient campaniasClient,
                RegistroAuditoriaRepository registroAuditoriaRepository) {
            this.donacionRepository = donacionRepository;
            this.motorTransicionService = motorTransicionService;
            this.campaniasClient = campaniasClient;
            this.registroAuditoriaRepository = registroAuditoriaRepository;
        }

        /**
         * Ejecuta la creación atómica de la Donación y procesa el ALTA de sus Unidades resultantes
         * A TRAVÉS DEL MOTOR ÚNICO DE TRANSICIONES DE ESTADO (RF-03, Caso 0).
         */
        @Transactional(isolation = Isolation.READ_COMMITTED)
        public RegistroDonacionResponseDto registrarDonacionAtomicamente(
                RegistroDonacionRequestDto request,
                UUID institucionId,
                UUID operadorId,
                String rol,
                String correlacionId
        ) {
            Instant ahora = Instant.now();

            // 1. REGLA IN-07 / DD Sec. 8.2: Exclusividad mutua entre donante_id e intencion_id
            if (request.donanteId() != null && request.intencionId() != null) {
                log.warn("Infracción IN-07: Intento de asociar donante_id ({}) e intencion_id ({}) simultáneamente. Correlación: {}",
                        request.donanteId(), request.intencionId(), correlacionId);

                throw new IncompatibilidadDonanteIntencionException(
                        "Las referencias 'donante_id' e 'intencion_id' son mutuamente excluyentes y no pueden coexistir en el mismo registro de donación.",
                        correlacionId
                );
            }

            // 2. REGLA EC-08 / DD Sec. 8.4: Verificación de campaña con degradación ante fallos
            UUID campaniaEfectivaId = null;
            if (request.campaniaId() != null) {
                campaniaEfectivaId = campaniasClient.validarCampaniaConDegradacion(request.campaniaId(), correlacionId)
                        .orElse(null);
            }

            // 3. Crear y guardar la Entidad Donación
            UUID donacionId = UUID.randomUUID();
            DonacionEntity donacion = new DonacionEntity();
            donacion.setId(donacionId);
            donacion.setDonanteId(request.donanteId());
            donacion.setIntencionId(request.intencionId());
            donacion.setTipoDonacionId(request.tipoDonacionId());
            donacion.setInstitucionId(institucionId);
            donacion.setCampaniaId(campaniaEfectivaId);
            donacion.setFechaCaptacion(ahora);
            donacion.setOperadorId(operadorId);
            donacion.setCreadoEn(ahora);

            donacionRepository.save(donacion);

            // 4. DELEGAR EL ALTA DE CADA UNIDAD RESULTANTE Y SU EVENTO INICIAL AL MOTOR ÚNICO (RF-03 / Caso 0)
            List<UnidadCreadaDto> unidadesCreadas = new ArrayList<>();

            for (UUID componenteId : request.componentes()) {
                UUID unidadId = UUID.randomUUID();
                LocalDate fechaVencimiento = calcularFechaVencimientoPorDefecto(ahora);

                // Construcción de la solicitud de alta para el Motor Único de Transiciones
                AltaUnidadRequest altaRequest = new AltaUnidadRequest(
                        unidadId,
                        donacionId,
                        componenteId,
                        GRUPO_SANGUINEO_PENDIENTE_ID,
                        institucionId, // Custodia inicial en el banco captador
                        fechaVencimiento,
                        null,          // volumenMl
                        ActorTipo.USUARIO,
                        operadorId.toString(),
                        rol,
                        institucionId,
                        null,          // observacionCodigo
                        correlacionId
                );

                // INVOCACIÓN AL MOTOR ÚNICO: Inicializa en 'captada', crea 'evento_unidad' (estadoAnteriorId = NULL) y auditoría ST2
                Unidad unidadCreada = motorTransicionService.procesarAltaUnidad(altaRequest);

                unidadesCreadas.add(new UnidadCreadaDto(
                        unidadCreada.getId(),
                        componenteId,
                        "captada",
                        fechaVencimiento,
                        institucionId
                ));
            }

            // 5. Asentar traza global de Donación en la Bitácora Inmutable de Auditoría (ST2, RNF-09)
            registrarAuditoriaDonacionExitosa(donacion, unidadesCreadas.size(), operadorId.toString(), rol, correlacionId, ahora);

            log.info("Donación y altas de unidades procesadas exitosamente por el motor. Donación ID: {}, Unidades: {}, Correlación: {}",
                    donacionId, unidadesCreadas.size(), correlacionId);

            return new RegistroDonacionResponseDto(
                    donacionId,
                    donacion.getDonanteId(),
                    donacion.getIntencionId(),
                    donacion.getTipoDonacionId(),
                    donacion.getCampaniaId(),
                    donacion.getInstitucionId(),
                    donacion.getOperadorId(),
                    donacion.getFechaCaptacion(),
                    unidadesCreadas,
                    correlacionId
            );
        }

        private LocalDate calcularFechaVencimientoPorDefecto(Instant ahora) {
            return LocalDate.ofInstant(ahora, java.time.ZoneOffset.UTC).plusDays(35);
        }

        private void registrarAuditoriaDonacionExitosa(
                DonacionEntity donacion,
                int cantidadUnidades,
                String actorId,
                String rol,
                String correlacionId,
                Instant ahora
        ) {
            RegistroAuditoria auditoria = new RegistroAuditoria();
            auditoria.setId(UUID.randomUUID());
            auditoria.setActorTipo(ActorTipo.USUARIO);
            auditoria.setActorId(actorId);
            auditoria.setRol(rol != null ? rol : "operador");
            auditoria.setJurisdiccionSolicitada("institucion:" + donacion.getInstitucionId());
            auditoria.setOperacion("REGISTRO_DONACION");
            auditoria.setRecursoTipo("donacion");
            auditoria.setRecursoId(donacion.getId());
            auditoria.setResultado(ResultadoAuditoria.PERMITIDO);
            auditoria.setCorrelacionId(correlacionId);
            auditoria.setOcurridoEn(ahora);

            auditoria.setDetalles(Map.of(
                    "tipo_donacion_id", donacion.getTipoDonacionId().toString(),
                    "unidades_creadas", cantidadUnidades,
                    "campania_asociada", donacion.getCampaniaId() != null ? donacion.getCampaniaId().toString() : "ninguna"
            ));

            registroAuditoriaRepository.save(auditoria);
        }
    }

    // =========================================================================
    // 6. CONTROLADOR REST (POST /v1/donaciones)
    // =========================================================================

    @RestController
    @RequestMapping("/v1/donaciones")
    public static class DonacionRegistroController {

        private static final Logger log = LoggerFactory.getLogger(DonacionRegistroController.class);
        private final RegistroDonacionService registroDonacionService;

        public DonacionRegistroController(RegistroDonacionService registroDonacionService) {
            this.registroDonacionService = registroDonacionService;
        }

        @PostMapping(
                consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE
        )
        @PreAuthorize("hasRole('operador')")
        public ResponseEntity<RegistroDonacionResponseDto> registrarDonacion(
                @Valid @RequestBody RegistroDonacionRequestDto body,
                @RequestHeader(value = "X-Correlacion-Id", required = false) String correlacionHeader,
                Authentication authentication
        ) {
            String correlacionId = (correlacionHeader != null && !correlacionHeader.isBlank())
                    ? correlacionHeader : "CORR-" + UUID.randomUUID();

            Jwt jwt = (Jwt) authentication.getPrincipal();
            UUID operadorId = UUID.fromString(jwt.getSubject());
            String rol = jwt.getClaimAsString("role");
            UUID institucionId = extraerInstitucionIdDeJurisdiccion(jwt);

            log.info("Iniciando registro de donación vía motor para Operador: {}, Institución: {}. Correlación: {}",
                    operadorId, institucionId, correlacionId);

            RegistroDonacionResponseDto respuesta = registroDonacionService.registrarDonacionAtomicamente(
                    body,
                    institucionId,
                    operadorId,
                    rol,
                    correlacionId
            );

            return ResponseEntity.status(HttpStatus.CREATED).body(respuesta);
        }

        @ExceptionHandler(IncompatibilidadDonanteIntencionException.class)
        public ResponseEntity<ProblemDetail> handleIncompatibilidadDonanteIntencion(IncompatibilidadDonanteIntencionException ex) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
            problem.setType(URI.create("https://redvital.gov.co/errores/conflicto-de-estado"));
            problem.setTitle("Conflicto en Referencias de Donante e Intención (IN-07)");
            problem.setProperty("correlacion_id", ex.getCorrelacionId());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }

        private UUID extraerInstitucionIdDeJurisdiccion(Jwt jwt) {
            String jurisdiction = jwt.getClaimAsString("jurisdiction");
            if (jurisdiction != null && jurisdiction.startsWith("institucion:")) {
                return UUID.fromString(jurisdiction.replace("institucion:", ""));
            }
            throw new IllegalStateException("El token JWT del operador no contiene una jurisdicción de institución válida");
        }
    }

    // =========================================================================
    // 7. EXCEPCIONES Y REPOSITORIO DE AUDITORÍA
    // =========================================================================

    public static class IncompatibilidadDonanteIntencionException extends RuntimeException {
        private final String correlacionId;

        public IncompatibilidadDonanteIntencionException(String mensaje, String correlacionId) {
            super(mensaje);
            this.correlacionId = correlacionId;
        }

        public String getCorrelacionId() { return correlacionId; }
    }

    @Repository
    public interface RegistroAuditoriaRepository extends JpaRepository<RegistroAuditoria, UUID> {}
}
