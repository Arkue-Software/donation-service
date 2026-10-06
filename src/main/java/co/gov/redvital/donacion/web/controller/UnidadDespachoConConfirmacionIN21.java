package co.gov.redvital.donacion.web.controller;

import co.gov.redvital.donacion.domain.exception.ConfirmacionInvalidaException;
import co.gov.redvital.donacion.domain.exception.TransicionEstadoInvalidaException;
import co.gov.redvital.donacion.domain.exception.UnidadNoEncontradaException;
import co.gov.redvital.donacion.domain.model.ActorTipo;
import co.gov.redvital.donacion.domain.model.EventoUnidad;
import co.gov.redvital.donacion.domain.model.Unidad;
import co.gov.redvital.donacion.domain.service.MotorTransicionEstadoUnidad;
import co.gov.redvital.donacion.domain.service.MotorTransicionEstadoUnidad.TransicionRequest;
import co.gov.redvital.donacion.infrastructure.repository.EstadoUnidadRepository;
import co.gov.redvital.donacion.infrastructure.repository.UnidadRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * =============================================================================
 * REDVITAL - Plataforma Nacional de Gestión de Bancos de Sangre
 * Servicio de Donación (redvital-donacion-service - Java 25 / Spring Boot 4.1)
 * =============================================================================
 *
 * OPERACIÓN DE DESPACHO DE UNIDAD DE SANGRE (POST /v1/unidades/{id}/despacho)
 *
 * Realiza el despacho para transfusión a través del Motor Único de Transiciones
 * de Estado cumpliendo estrictamente con las reglas normativas y de calidad:
 * - IN-21 / EC-39: Despacho restringido EXCLUSIVAMENTE a la institución custodia de la unidad.
 * - IN-22 / EC-12 / RNI-04: Despacho permitido ÚNICAMENTE sobre unidades aptas (apta = true) y no vencidas.
 * - EC-19 / EC-41 / DD Sec. 12.1: Campo 'confirmacion' obligatorio con el UUID exacto de la unidad.
 *   Si no coincide, se rechaza con HTTP 422 Unprocessable Entity MANTENIENDO EL ESTADO INTACTO.
 * - Transición 7: Transición oficial ('reservada' -> 'despachada', actor: USUARIO) en el catálogo transicion_valida.
 * - EC-02 / RI-02: Ausencia total de campos o causas clínicas en la petición, respuesta y auditoría.
 */
@RestController
@RequestMapping("/v1/unidades")
public class UnidadDespachoConConfirmacionIN21 {

    private static final Logger log = LoggerFactory.getLogger(UnidadDespachoConConfirmacionIN21.class);

    private static final String ESTADO_RESERVADA = "reservada";
    private static final String ESTADO_DESPACHADA = "despachada";
    private static final String ESTADO_DISPONIBLE = "disponible";

    private final MotorTransicionEstadoUnidad motorTransicionService;
    private final UnidadRepository unidadRepository;
    private final EstadoUnidadRepository estadoUnidadRepository;

    public UnidadDespachoConConfirmacionIN21(
            MotorTransicionEstadoUnidad motorTransicionService,
            UnidadRepository unidadRepository,
            EstadoUnidadRepository estadoUnidadRepository) {
        this.motorTransicionService = motorTransicionService;
        this.unidadRepository = unidadRepository;
        this.estadoUnidadRepository = estadoUnidadRepository;
    }

    /**
     * DTO de Solicitud de Despacho con Esquema Estricto (DD Sec. 12.1, EC-19, EC-41).
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DespachoRequestDto(
            @NotBlank(message = "El campo 'confirmacion' es obligatorio y debe coincidir con el identificador de la unidad.")
            String confirmacion,

            String observacionCodigo // Opcional, catálogo observacion_operativa (D-02)
    ) {}

    /**
     * DTO de Respuesta de Despacho Exitoso.
     */
    public record DespachoResponseDto(
            UUID eventoId,
            UUID unidadId,
            String estadoAnterior,
            String estadoNuevo,
            UUID institucionCustodiaId,
            String actorId,
            ActorTipo actorTipo,
            String correlacionId,
            Instant ocurridoEn
    ) {}

    /**
     * Endpoint REST de Despacho para Transfusión (POST /v1/unidades/{id}/despacho).
     *
     * @param id Identificador UUID de la unidad a despachar
     * @param body Causal con confirmación obligatoria
     * @param correlacionHeader Cabecera de trazabilidad distribuida X-Correlacion-Id
     * @param authentication Contexto de seguridad JWT
     */
    @PostMapping(
            value = "/{id}/despacho",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('operador')")
    @Transactional
    public ResponseEntity<DespachoResponseDto> despacharUnidad(
            @PathVariable("id") UUID id,
            @Valid @RequestBody DespachoRequestDto body,
            @RequestHeader(value = "X-Correlacion-Id", required = false) String correlacionHeader,
            Authentication authentication
    ) {
        String correlacionId = (correlacionHeader != null && !correlacionHeader.isBlank())
                ? correlacionHeader : "CORR-" + UUID.randomUUID();

        // 1. CONFIRMACIÓN OBLIGATORIA (EC-19, EC-41, DD Sec. 12.1)
        // El campo confirmacion debe ser exactamente igual al id de la unidad
        if (body == null || body.confirmacion() == null || !body.confirmacion().trim().equalsIgnoreCase(id.toString())) {
            String valorRecibido = body != null ? body.confirmacion() : null;

            log.warn("Intento de despacho sin confirmación válida. Unidad: {}, Recibido: '{}', Correlación: {}",
                    id, valorRecibido, correlacionId);

            // LANZA HTTP 422 UNPROCESSABLE ENTITY - ESTADO DE LA UNIDAD INTACTO
            throw new ConfirmacionInvalidaException(
                    String.format("El campo 'confirmacion' es obligatorio y debe coincidir exactamente con el identificador de la unidad (%s). Valor recibido: '%s'.",
                            id, valorRecibido != null ? valorRecibido : "null"),
                    id.toString(),
                    valorRecibido,
                    correlacionId
            );
        }

        // 2. Extraer contexto de seguridad JWT del Operador (U3)
        Jwt jwt = (Jwt) authentication.getPrincipal();
        String actorId = jwt.getSubject();
        String rol = jwt.getClaimAsString("role");
        UUID institucionOperadorId = extraerInstitucionIdDeJurisdiccion(jwt);

        // 3. Cargar unidad y validar las Reglas de Negocio IN-21 e IN-22 (Antes de invocar el motor)
        Unidad unidad = unidadRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new UnidadNoEncontradaException(id, correlacionId));

        // A. REGLA IN-21 / EC-39: Solo la institución custodia de la unidad puede ejecutar el despacho
        if (!unidad.getInstitucionCustodiaId().equals(institucionOperadorId)) {
            log.warn("Infracción IN-21 / EC-39: Intento de despacho por institución no custodia. Unidad Custodia: {}, Operador Institución: {}, Correlación: {}",
                    unidad.getInstitucionCustodiaId(), institucionOperadorId, correlacionId);

            throw new ViolacionReglaOperativaIN21Exception(
                    String.format("La unidad %s está bajo la custodia de la institución %s y no puede ser despachada por la institución %s.",
                            id, unidad.getInstitucionCustodiaId(), institucionOperadorId),
                    id.toString(),
                    unidad.getInstitucionCustodiaId().toString(),
                    institucionOperadorId.toString(),
                    correlacionId
            );
        }

        // B. REGLA IN-22 / EC-12 / RNI-04: Solo se permite sobre unidades aptas (apta = true) y no vencidas
        LocalDate fechaActual = LocalDate.now();
        boolean esApta = Boolean.TRUE.equals(unidad.getApta());
        boolean noEstaVencida = unidad.getFechaVencimiento() != null && !unidad.getFechaVencimiento().isBefore(fechaActual);

        if (!esApta || !noEstaVencida) {
            log.warn("Infracción IN-22 / EC-12: Intento de despacho sobre unidad no apta o vencida. Unidad: {}, Apta: {}, Vencimiento: {}, Correlación: {}",
                    id, unidad.getApta(), unidad.getFechaVencimiento(), correlacionId);

            throw new TransicionEstadoInvalidaException(
                    String.format("La unidad %s no está disponible para despacho debido a condición de aptitud (apta=%s) o fecha de vencimiento expirada (%s).",
                            id, unidad.getApta(), unidad.getFechaVencimiento()),
                    obtenerCodigoEstado(unidad.getEstadoId()),
                    ESTADO_DESPACHADA,
                    correlacionId
            );
        }

        // 4. PREPARACIÓN DE TRANSICIÓN PARA EL MOTOR ÚNICO
        // Si la unidad está en 'disponible', primero se transiciona a 'reservada' (Transición 5)
        // para cumplir el orden formal de la máquina de estados ('reservada' -> 'despachada' = Transición 7)
        String estadoOrigenInicial = obtenerCodigoEstado(unidad.getEstadoId());

        if (ESTADO_DISPONIBLE.equals(estadoOrigenInicial)) {
            TransicionRequest requestReserva = new TransicionRequest(
                    id,
                    ESTADO_RESERVADA,
                    ActorTipo.USUARIO,
                    actorId,
                    rol,
                    institucionOperadorId,
                    body.observacionCodigo(),
                    null,
                    null,
                    correlacionId
            );
            motorTransicionService.procesarTransicion(requestReserva);
        }

        // 5. EJECUCIÓN DE TRANSICIÓN 7 ('reservada' -> 'despachada') EN EL MOTOR ÚNICO (IN-04)
        TransicionRequest requestDespacho = new TransicionRequest(
                id,
                ESTADO_DESPACHADA,
                ActorTipo.USUARIO,
                actorId,
                rol,
                institucionOperadorId,
                body.observacionCodigo(),
                null,
                null,
                correlacionId
        );

        EventoUnidad eventoGenerado = motorTransicionService.procesarTransicion(requestDespacho);

        DespachoResponseDto response = new DespachoResponseDto(
                eventoGenerado.getId(),
                eventoGenerado.getUnidadId(),
                ESTADO_RESERVADA,
                ESTADO_DESPACHADA,
                unidad.getInstitucionCustodiaId(),
                eventoGenerado.getActorId(),
                eventoGenerado.getActorTipo(),
                eventoGenerado.getCorrelacionId(),
                eventoGenerado.getOcurridoEn()
        );

        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // MANEJADORES DE EXCEPCIONES Y ERRORES ESTANDARIZADOS (RFC 9457)
    // =========================================================================

    @ExceptionHandler(ConfirmacionInvalidaException.class)
    public ResponseEntity<ProblemDetail> handleConfirmacionInvalida(ConfirmacionInvalidaException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setType(URI.create("https://redvital.gov.co/errores/regla-de-negocio"));
        problem.setTitle("Confirmación de Acción Irreversible Inválida");
        problem.setProperty("recurso_id_esperado", ex.getRecursoIdEsperado());
        problem.setProperty("confirmacion_recibida", ex.getConfirmacionRecibida());
        problem.setProperty("correlacion_id", ex.getCorrelacionId());
        problem.setProperty("estado_unidad_modificado", false);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    @ExceptionHandler(ViolacionReglaOperativaIN21Exception.class)
    public ResponseEntity<ProblemDetail> handleViolacionReglaIN21(ViolacionReglaOperativaIN21Exception ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setType(URI.create("https://redvital.gov.co/errores/regla-de-negocio"));
        problem.setTitle("Violación de Regla Operativa de Despacho (IN-21)");
        problem.setProperty("unidad_id", ex.getUnidadId());
        problem.setProperty("institucion_custodia_id", ex.getInstitucionCustodiaId());
        problem.setProperty("institucion_operador_id", ex.getInstitucionOperadorId());
        problem.setProperty("correlacion_id", ex.getCorrelacionId());
        problem.setProperty("estado_unidad_modificado", false);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    @ExceptionHandler(TransicionEstadoInvalidaException.class)
    public ResponseEntity<ProblemDetail> handleTransicionInvalida(TransicionEstadoInvalidaException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://redvital.gov.co/errores/conflicto-de-estado"));
        problem.setTitle("Transición de Despacho No Permitida");
        problem.setProperty("estado_actual", ex.getEstadoOrigen());
        problem.setProperty("estado_pretendido", ex.getEstadoDestino());
        problem.setProperty("correlacion_id", ex.getCorrelacionId());
        problem.setProperty("estado_unidad_modificado", false);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(UnidadNoEncontradaException.class)
    public ResponseEntity<ProblemDetail> handleUnidadNoEncontrada(UnidadNoEncontradaException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(URI.create("https://redvital.gov.co/errores/no-encontrado"));
        problem.setTitle("Unidad No Encontrada");
        problem.setProperty("correlacion_id", ex.getCorrelacionId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    private UUID extraerInstitucionIdDeJurisdiccion(Jwt jwt) {
        String jurisdiction = jwt.getClaimAsString("jurisdiction");
        if (jurisdiction != null && jurisdiction.startsWith("institucion:")) {
            return UUID.fromString(jurisdiction.replace("institucion:", ""));
        }
        throw new IllegalStateException("El token del operador no posee una jurisdicción de institución válida");
    }

    private String obtenerCodigoEstado(UUID estadoId) {
        return estadoUnidadRepository.findById(estadoId)
                .map(e -> e.getCodigo())
                .orElse("desconocido");
    }

    /**
     * Excepción de Dominio para violaciones de la Regla IN-21.
     */
    public static class ViolacionReglaOperativaIN21Exception extends RuntimeException {
        private final String unidadId;
        private final String institucionCustodiaId;
        private final String institucionOperadorId;
        private final String correlacionId;

        public ViolacionReglaOperativaIN21Exception(
                String mensaje,
                String unidadId,
                String institucionCustodiaId,
                String institucionOperadorId,
                String correlacionId) {
            super(mensaje);
            this.unidadId = unidadId;
            this.institucionCustodiaId = institucionCustodiaId;
            this.institucionOperadorId = institucionOperadorId;
            this.correlacionId = correlacionId;
        }

        public String getUnidadId() { return unidadId; }
        public String getInstitucionCustodiaId() { return institucionCustodiaId; }
        public String getInstitucionOperadorId() { return institucionOperadorId; }
        public String getCorrelacionId() { return correlacionId; }
    }
}
