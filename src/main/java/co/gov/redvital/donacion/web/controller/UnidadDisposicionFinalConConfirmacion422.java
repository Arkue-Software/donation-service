package co.gov.redvital.donacion.web.controller;

import co.gov.redvital.donacion.domain.exception.ConfirmacionInvalidaException;
import co.gov.redvital.donacion.domain.exception.TransicionEstadoInvalidaException;
import co.gov.redvital.donacion.domain.exception.UnidadNoEncontradaException;
import co.gov.redvital.donacion.domain.model.ActorTipo;
import co.gov.redvital.donacion.domain.model.EventoUnidad;
import co.gov.redvital.donacion.domain.service.MotorTransicionEstadoUnidad;
import co.gov.redvital.donacion.domain.service.MotorTransicionEstadoUnidad.TransicionRequest;
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
 * REDVITAL - Servicio de Donación (redvital-donacion-service)
 * Operación Irreversible: Disposición Final de Unidad con Campo de Confirmación Obligatorio.
 *
 * Cumplimiento de Especificación DD V2.0 / SRS V3.0 / SAD V2.0:
 * - RF-06: Protocolo de disposición final (desecho físico de la unidad).
 * - Transición 12: 'no_apta' -> 'desechada' (actor: 'usuario')
 * - Transición 13: 'vencida' -> 'desechada' (actor: 'usuario')
 * - EC-19 / EC-41 / DD Sec 12.1: Exige en el cuerpo de la petición el campo 'confirmacion'
 *   con el UUID exacto de la unidad.
 * - Rechazo estricto HTTP 422 Unprocessable Entity (RFC 9457) SIN cambio de estado
 *   en la base de datos si 'confirmacion' no coincide.
 * - Invocación delegada al Motor Único de Transiciones de Estado (MotorTransicionEstadoUnidad).
 */
@RestController
@RequestMapping("/v1/unidades")
public class OperacionDisposicionFinalConfirmacionEC19 {

    private static final Logger log = LoggerFactory.getLogger(OperacionDisposicionFinalConfirmacionEC19.class);
    private static final String ESTADO_DESTINO_DESECHADA = "desechada";

    private final MotorTransicionEstadoUnidad motorTransicionService;

    public OperacionDisposicionFinalConfirmacionEC19(MotorTransicionEstadoUnidad motorTransicionService) {
        this.motorTransicionService = motorTransicionService;
    }

    /**
     * DTO de entrada para la solicitud de disposición final.
     * @param confirmacion Debe coincidir exactamente con el UUID de la unidad que se va a desechar.
     * @param observacionCodigo Código opcional del catálogo observacion_operativa (ej. 'desecho_biologico').
     */
    public record DisposicionFinalRequestDto(
            String confirmacion,
            String observacionCodigo
    ) {}

    /**
     * DTO de respuesta para la operación de disposición final realizada.
     */
    public record DisposicionFinalResponseDto(
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
     * Endpoint REST: POST /v1/unidades/{id}/disposicion-final
     *
     * @param id Identificador UUID de la unidad de sangre.
     * @param body Cuerpo JSON con el campo 'confirmacion' obligatorio.
     * @param correlacionHeader Identificador de correlación para trazabilidad distribuida (EC-20).
     * @param authentication Token JWT del operador autenticado (U3).
     * @return 200 OK con el evento generado si la confirmación y la transición son válidas.
     */
    @PostMapping(
            value = "/{id}/disposicion-final",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('operador')")
    public ResponseEntity<DisposicionFinalResponseDto> registrarDisposicionFinal(
            @PathVariable("id") UUID id,
            @RequestBody(required = false) DisposicionFinalRequestDto body,
            @RequestHeader(value = "X-Correlacion-Id", required = false) String correlacionHeader,
            Authentication authentication
    ) {
        String correlacionId = (correlacionHeader != null && !correlacionHeader.isBlank())
                ? correlacionHeader : "CORR-" + UUID.randomUUID();

        // 1. VALIDACIÓN PREVIA DE SEGURIDAD OPERATIVA (EC-19 / EC-41 / DD Sec 12.1):
        // Si el campo 'confirmacion' no coincide exactamente con el UUID de la unidad (id.toString()),
        // la operación se rechaza inmediatamente SIN modificar el estado de la unidad en la base de datos.
        if (body == null || body.confirmacion() == null || !body.confirmacion().trim().equalsIgnoreCase(id.toString())) {
            String valorRecibido = body != null ? body.confirmacion() : null;
            log.warn("Disposición final RECHAZADA por confirmación no coincidente. Unidad: {}, Recibido: {}, Correlación: {}",
                    id, valorRecibido, correlacionId);

            // Se lanza la excepción que la capa web mapea a HTTP 422 Unprocessable Entity
            throw new ConfirmacionInvalidaException(
                    String.format("El campo 'confirmacion' es obligatorio y debe coincidir exactamente con el identificador de la unidad (%s). Valor recibido: '%s'.",
                            id, valorRecibido != null ? valorRecibido : "null"),
                    id.toString(),
                    valorRecibido,
                    correlacionId
            );
        }

        // 2. Extraer los atributos de seguridad del token JWT del Operador (U3)
        Jwt jwt = (Jwt) authentication.getPrincipal();
        String actorId = jwt.getSubject();
        String rol = jwt.getClaimAsString("role");
        UUID institucionId = extraerInstitucionIdDeJurisdiccion(jwt);

        // 3. Construir la solicitud para el Motor Único de Transiciones de Estado
        // Transiciones permitidas por catálogo: Transición 12 (no_apta -> desechada) o Transición 13 (vencida -> desechada)
        TransicionRequest request = new TransicionRequest(
                id,
                ESTADO_DESTINO_DESECHADA,
                ActorTipo.USUARIO, // Exige actor humano (DD Sec 10.2)
                actorId,
                rol,
                institucionId,
                body.observacionCodigo(),
                null, // apta no se modifica
                null,
                correlacionId
        );

        // 4. Delegar la transacción atómica al motor (actualiza estado, inserta evento_unidad y auditoría)
        EventoUnidad eventoGenerado = motorTransicionService.procesarTransicion(request);

        DisposicionFinalResponseDto response = new DisposicionFinalResponseDto(
                eventoGenerado.getId(),
                eventoGenerado.getUnidadId(),
                "no_apta_o_vencida",
                ESTADO_DESTINO_DESECHADA,
                eventoGenerado.getActorId(),
                eventoGenerado.getActorTipo(),
                eventoGenerado.getInstitucionId(),
                eventoGenerado.getCorrelacionId(),
                eventoGenerado.getOcurridoEn()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Manejador de Error HTTP 422 (Unprocessable Entity - RFC 9457)
     * Disparado cuando el campo 'confirmacion' no coincide. Garantiza que la respuesta
     * detalle el error de regla de negocio sin haber modificado el estado de la unidad.
     */
    @ExceptionHandler(ConfirmacionInvalidaException.class)
    public ResponseEntity<ProblemDetail> handleConfirmacionInvalida(ConfirmacionInvalidaException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setType(URI.create("https://redvital.gov.co/errores/regla-de-negocio"));
        problem.setTitle("Confirmación de Acción Irreversible Inválida");
        problem.setProperty("recurso_id_esperado", ex.getRecursoIdEsperado());
        problem.setProperty("confirmacion_recibida", ex.getConfirmacionRecibida());
        problem.setProperty("correlacion_id", ex.getCorrelacionId());
        problem.setProperty("estado_unidad_modificado", false); // Garantía explícita de inmutabilidad
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    /**
     * Manejador de Error HTTP 409 (Conflict - RFC 9457)
     * Disparado si la unidad no se encontraba en estado 'no_apta' ni 'vencida'
     * (por ejemplo, si estaba en 'disponible' o 'captada').
     */
    @ExceptionHandler(TransicionEstadoInvalidaException.class)
    public ResponseEntity<ProblemDetail> handleTransicionInvalida(TransicionEstadoInvalidaException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://redvital.gov.co/errores/conflicto-de-estado"));
        problem.setTitle("Transición No Admitida para Disposición Final");
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
}
