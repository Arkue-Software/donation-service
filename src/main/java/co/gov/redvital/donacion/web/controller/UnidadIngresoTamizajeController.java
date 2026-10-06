package co.gov.redvital.donacion.web.controller;

import co.gov.redvital.donacion.domain.model.ActorTipo;
import co.gov.redvital.donacion.domain.model.EventoUnidad;
import co.gov.redvital.donacion.domain.service.MotorTransicionEstadoUnidad;
import co.gov.redvital.donacion.domain.service.MotorTransicionEstadoUnidad.TransicionRequest;
import co.gov.redvital.donacion.domain.exception.TransicionEstadoInvalidaException;
import co.gov.redvital.donacion.domain.exception.UnidadNoEncontradaException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * Controlador REST para la operación de ingreso a tamizaje de una unidad de sangre.
 *
 * Módulo: M3 - Trazabilidad y Ciclo de Vida de la Unidad (redvital-donacion-service).
 * Operación: POST /v1/unidades/{id}/ingreso-tamizaje
 * Transición DD: Transición 2 (fraccionada -> en_tamizaje)
 *
 * Cumplimiento de Especificaciones y Calidad:
 * - RF-05 / Tabla 19 DD V2.0: Inicia el proceso de laboratorio de la unidad (fraccionada -> en_tamizaje).
 * - IN-04 / IN-05: Invoca al MotorTransicionEstadoUnidad como punto único de control de la máquina de estados.
 * - EC-01 / ADR-011: Restringido al rol U3 (operador de banco) dentro de la institución custodia.
 * - EC-02 / RI-02 / Anexo A: Ausencia total de campos o datos de causa clínica o pruebas diagnósticas.
 * - EC-20: Garantiza la propagación y registro de X-Correlacion-Id.
 * - RFC 9457: Respuestas de error estandarizadas con Problem Details en HTTP 404 y 409.
 */
@RestController
@RequestMapping("/v1/unidades")
@Tag(name = "Trazabilidad y Ciclo de Vida", description = "Operaciones del ciclo de vida de la unidad de sangre")
public class UnidadIngresoTamizajeController {

    private static final Logger log = LoggerFactory.getLogger(UnidadIngresoTamizajeController.class);
    private static final String ESTADO_DESTINO_INGRESO_TAMIZAJE = "en_tamizaje";

    private final MotorTransicionEstadoUnidad motorTransicionService;

    public UnidadIngresoTamizajeController(MotorTransicionEstadoUnidad motorTransicionService) {
        this.motorTransicionService = motorTransicionService;
    }

    /**
     * DTO de entrada para la petición de ingreso a tamizaje.
     * Permite incluir opcionalmente un código de observación operativa del catálogo (D-02).
     */
    public record IngresoTamizajeRequestDto(
            @Schema(description = "Código de observación operativa opcional (catálogo observacion_operativa)", example = "traslado_interno")
            String observacionCodigo
    ) {}

    /**
     * DTO de respuesta para el evento de ingreso a tamizaje generado.
     */
    public record IngresoTamizajeResponseDto(
            UUID eventoId,
            UUID unidadId,
            String estadoAnterior,
            String estadoNuevo,
            String actorId,
            ActorTipo actorTipo,
            UUID institucionId,
            String correlacionId,
            Instant ocurridoEn
    ) {}

    /**
     * POST /v1/unidades/{id}/ingreso-tamizaje
     *
     * Registra el ingreso a tamizaje de una unidad de sangre en estado 'fraccionada',
     * transitando su estado a 'en_tamizaje' a través del motor único de transiciones.
     *
     * @param id Identificador de la unidad de sangre
     * @param body Cúerpo con observación opcional de catálogo
     * @param correlacionHeader Cabecera de trazabilidad distribuida (X-Correlacion-Id)
     * @param authentication Token JWT de seguridad Spring Security
     * @return 200 OK con el detalle del evento generado
     */
    @PostMapping(
            value = "/{id}/ingreso-tamizaje",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('operador')") // Exclusivo para U3 (Operador de banco) por Matriz ADR-011
    @Operation(
            summary = "Iniciar tamizaje de una unidad de sangre",
            description = "Ejecuta la Transición 2 del catálogo normativo (fraccionada -> en_tamizaje) delegando al motor único de estados."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ingreso a tamizaje registrado exitosamente"),
            @ApiResponse(responseCode = "400", description = "Petición malformada o sintaxis inválida", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Sesión no válida o token ausente/expirado", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Acceso denegado o rol/jurisdicción insuficiente", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Unidad no encontrada dentro de la jurisdicción del operador", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Conflicto de estado: La unidad no está en estado 'fraccionada'", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<IngresoTamizajeResponseDto> ingresarATamizaje(
            @Parameter(description = "UUID de la unidad de sangre", required = true)
            @PathVariable("id") UUID id,

            @RequestBody(required = false) IngresoTamizajeRequestDto body,

            @Parameter(description = "Identificador de correlación HTTP", required = false)
            @RequestHeader(value = "X-Correlacion-Id", required = false) String correlacionHeader,

            Authentication authentication
    ) {
        // 1. Extraer y garantizar el identificador de correlación (EC-20)
        String correlacionId = resolverCorrelacionId(correlacionHeader);

        // 2. Extraer atributos de seguridad del token JWT del operador (sub, jurisdiction, role)
        Jwt jwt = (Jwt) authentication.getPrincipal();
        String actorId = jwt.getSubject(); // Claim 'sub'
        String rol = jwt.getClaimAsString("role"); // Claim 'role' (esperado 'operador' / U3)
        UUID institucionId = extraerInstitucionIdDeJurisdiccion(jwt);

        log.info("Procesando ingreso a tamizaje. Unidad: {}, Operador: {}, Institución: {}, Correlación: {}",
                id, actorId, institucionId, correlacionId);

        // 3. Construir la solicitud formal para el motor transaccional
        String observacionCodigo = body != null ? body.observacionCodigo() : null;
        TransicionRequest transicionRequest = new TransicionRequest(
                id,
                ESTADO_DESTINO_INGRESO_TAMIZAJE, // "en_tamizaje"
                ActorTipo.USUARIO,                // Transición operada por humano (operador U3)
                actorId,
                rol,
                institucionId,
                observacionCodigo,
                null,                             // veredictoApta = null (se asigna en /tamizaje, no en ingreso)
                null,                             // nuevaInstitucionCustodiaId = null
                correlacionId
        );

        // 4. Invocación del Motor Único de Transiciones
        EventoUnidad eventoGenerado = motorTransicionService.procesarTransicion(transicionRequest);

        // 5. Construir la respuesta exitosa
        IngresoTamizajeResponseDto response = new IngresoTamizajeResponseDto(
                eventoGenerado.getId(),
                eventoGenerado.getUnidadId(),
                "fraccionada",
                ESTADO_DESTINO_INGRESO_TAMIZAJE,
                eventoGenerado.getActorId(),
                eventoGenerado.getActorTipo(),
                eventoGenerado.getInstitucionId(),
                eventoGenerado.getCorrelacionId(),
                eventoGenerado.getOcurridoEn()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Manejador de excepción para conflicto de estado de la unidad (HTTP 409 RFC 9457).
     * Ocurre si la unidad no está en estado 'fraccionada' o la transición es inválida.
     */
    @ExceptionHandler(TransicionEstadoInvalidaException.class)
    public ResponseEntity<ProblemDetail> handleTransicionInvalida(TransicionEstadoInvalidaException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://redvital.gov.co/errores/conflicto-de-estado"));
        problem.setTitle("Transición de Estado No Permitida");
        problem.setProperty("estado_actual", ex.getEstadoOrigen());
        problem.setProperty("estado_pretendido", ex.getEstadoDestino());
        problem.setProperty("correlacion_id", ex.getCorrelacionId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * Manejador de excepción para unidad no encontrada o fuera de jurisdicción (HTTP 404 RFC 9457).
     */
    @ExceptionHandler(UnidadNoEncontradaException.class)
    public ResponseEntity<ProblemDetail> handleUnidadNoEncontrada(UnidadNoEncontradaException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(URI.create("https://redvital.gov.co/errores/no-encontrado"));
        problem.setTitle("Recurso No Encontrado");
        problem.setProperty("correlacion_id", ex.getCorrelacionId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    // --- Métodos Auxiliares de Seguridad y Trazabilidad ---

    private String resolverCorrelacionId(String header) {
        if (header != null && !header.isBlank()) {
            return header;
        }
        return "CORR-" + UUID.randomUUID().toString();
    }

    private UUID extraerInstitucionIdDeJurisdiccion(Jwt jwt) {
        String jurisdiction = jwt.getClaimAsString("jurisdiction");
        if (jurisdiction != null && jurisdiction.startsWith("institucion:")) {
            try {
                return UUID.fromString(jurisdiction.replace("institucion:", ""));
            } catch (IllegalArgumentException e) {
                log.warn("Formato de jurisdicción de institución inválido en token JWT: {}", jurisdiction);
            }
        }
        throw new IllegalStateException("El token del operador no posee una jurisdicción de institución válida");
    }
}
