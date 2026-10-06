package co.gov.redvital.donacion.web.controller;

import co.gov.redvital.donacion.domain.exception.TransicionEstadoInvalidaException;
import co.gov.redvital.donacion.domain.exception.UnidadNoEncontradaException;
import co.gov.redvital.donacion.domain.model.ActorTipo;
import co.gov.redvital.donacion.domain.model.EventoUnidad;
import co.gov.redvital.donacion.domain.service.MotorTransicionEstadoUnidad;
import co.gov.redvital.donacion.domain.service.MotorTransicionEstadoUnidad.TransicionRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
 * Controlador REST para el Registro del Veredicto de Tamizaje en Unidades de Sangre (RF-05).
 *
 * Cumple estrictamente con las reglas del Documento de Diseño (DD V2.0, Sección 14.4) y el SAD V2.0:
 * - RF-05 / Transición 3 (en_tamizaje -> disponible) y Transición 4 (en_tamizaje -> no_apta).
 * - EC-02 / RI-02 / IN-01 (Confidencialidad de la Causa Clínica): Esquema estricto de entrada.
 *   El contrato NO admite ni modela ningún campo de motivo clínico, prueba diagnóstica o marcador serológico.
 *   Se configura @JsonIgnoreProperties(ignoreUnknown = false) para rechazar con HTTP 400 cualquier propiedad
 *   no declarada en el cuerpo de la petición.
 * - Catálogo observacion_operativa (D-02): Permite opcionalmente un código de anotación exclusivamente operativa
 *   (ej. 'control_temperatura', 'reetiquetado'), garantizando que no exista campo para texto libre.
 * - Delegación al Motor Único de Transiciones de Estado (IN-04, EC-36).
 */
@RestController
@RequestMapping("/v1/unidades")
public class UnidadTamizajeVeredictoController {

    private static final String ESTADO_DESTINO_DISPONIBLE = "disponible";
    private static final String ESTADO_DESTINO_NO_APTA = "no_apta";

    private final MotorTransicionEstadoUnidad motorTransicionService;

    public UnidadTamizajeVeredictoController(MotorTransicionEstadoUnidad motorTransicionService) {
        this.motorTransicionService = motorTransicionService;
    }

    /**
     * DTO de entrada con Esquema Estricto (DD Sec. 14.4, EC-02).
     *
     * La anotación @JsonIgnoreProperties(ignoreUnknown = false) actúa como barrera de seguridad:
     * si un cliente o atacante envía propiedades adicionales como 'causa', 'motivo_rechazo' o 'resultado_vih',
     * la deserialización JSON falla inmediatamente retornando HTTP 400 Bad Request antes de tocar la base de datos.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TamizajeVeredictoRequestDto(
            @NotNull(message = "El veredicto de aptitud 'apta' es obligatorio (true o false).")
            Boolean apta,

            String observacionCodigo // Opcional, exclusivamente del catálogo observacion_operativa (D-02)
    ) {}

    /**
     * DTO de respuesta que expone el evento generado sin ningún dato clínico.
     */
    public record TamizajeVeredictoResponseDto(
            UUID eventoId,
            UUID unidadId,
            String estadoAnterior,
            String estadoNuevo,
            Boolean apta,
            String actorId,
            ActorTipo actorTipo,
            UUID institucionId,
            String correlacionId,
            Instant ocurridoEn
    ) {}

    /**
     * Registra el veredicto de tamizaje de una unidad de sangre.
     *
     * @param id Identificador UUID de la unidad en tamizaje
     * @param body Carga útil con la bandera 'apta' y código opcional de observacion_operativa
     * @param correlacionHeader Cabecera opcional para trazabilidad distribuida X-Correlacion-Id (EC-20)
     * @param authentication Contexto de seguridad del usuario autenticado
     */
    @PostMapping(
            value = "/{id}/tamizaje",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('operador')")
    public ResponseEntity<TamizajeVeredictoResponseDto> registrarVeredictoTamizaje(
            @PathVariable("id") UUID id,
            @Valid @RequestBody TamizajeVeredictoRequestDto body,
            @RequestHeader(value = "X-Correlacion-Id", required = false) String correlacionHeader,
            Authentication authentication
    ) {
        String correlacionId = (correlacionHeader != null && !correlacionHeader.isBlank())
                ? correlacionHeader : "CORR-" + UUID.randomUUID();

        // 1. Determinar el estado destino en función del veredicto booleano
        // Transición 3 (apta = true -> 'disponible') o Transición 4 (apta = false -> 'no_apta')
        String estadoDestinoCodigo = Boolean.TRUE.equals(body.apta())
                ? ESTADO_DESTINO_DISPONIBLE
                : ESTADO_DESTINO_NO_APTA;

        // 2. Extraer contexto de seguridad del Operador de Banco (U3)
        Jwt jwt = (Jwt) authentication.getPrincipal();
        String actorId = jwt.getSubject();
        String rol = jwt.getClaimAsString("role");
        UUID institucionId = extraerInstitucionIdDeJurisdiccion(jwt);

        // 3. Construir solicitud de transición para el motor único
        TransicionRequest request = new TransicionRequest(
                id,
                estadoDestinoCodigo,
                ActorTipo.USUARIO,
                actorId,
                rol,
                institucionId,
                body.observacionCodigo(), // Código de observacion_operativa si aplica
                body.apta(),              // Fija unidad.apta = true/false (sin causa clínica)
                null,
                correlacionId
        );

        // 4. Delegar la ejecución atómica al motor único
        EventoUnidad eventoGenerado = motorTransicionService.procesarTransicion(request);

        TamizajeVeredictoResponseDto response = new TamizajeVeredictoResponseDto(
                eventoGenerado.getId(),
                eventoGenerado.getUnidadId(),
                "en_tamizaje",
                estadoDestinoCodigo,
                body.apta(),
                eventoGenerado.getActorId(),
                eventoGenerado.getActorTipo(),
                eventoGenerado.getInstitucionId(),
                eventoGenerado.getCorrelacionId(),
                eventoGenerado.getOcurridoEn()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Manejador de error para transiciones no válidas en la máquina de estados (HTTP 409 Conflict).
     * Si la unidad no está en estado 'en_tamizaje', el motor rechaza el cambio y el estado permanece intacto.
     */
    @ExceptionHandler(TransicionEstadoInvalidaException.class)
    public ResponseEntity<ProblemDetail> handleTransicionInvalida(TransicionEstadoInvalidaException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://redvital.gov.co/errores/conflicto-de-estado"));
        problem.setTitle("Transición de Tamizaje No Permitida");
        problem.setProperty("estado_actual", ex.getEstadoOrigen());
        problem.setProperty("estado_pretendido", ex.getEstadoDestino());
        problem.setProperty("correlacion_id", ex.getCorrelacionId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * Manejador de error para unidades no encontradas en el ámbito del operador (HTTP 404 Not Found).
     */
    @ExceptionHandler(UnidadNoEncontradaException.class)
    public ResponseEntity<ProblemDetail> handleUnidadNoEncontrada(UnidadNoEncontradaException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(URI.create("https://redvital.gov.co/errores/no-encontrado"));
        problem.setTitle("Unidad No Encontrada");
        problem.setProperty("correlacion_id", ex.getCorrelacionId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * Extrae el identificador UUID de la institución a partir del claim 'jurisdiction' del JWT.
     */
    private UUID extraerInstitucionIdDeJurisdiccion(Jwt jwt) {
        String jurisdiction = jwt.getClaimAsString("jurisdiction");
        if (jurisdiction != null && jurisdiction.startsWith("institucion:")) {
            return UUID.fromString(jurisdiction.replace("institucion:", ""));
        }
        throw new IllegalStateException("El token del operador no posee una jurisdicción de institución válida");
    }
}
