package co.gov.redvital.donacion.domain.service;

import co.gov.redvital.donacion.domain.model.ActorTipo;
import co.gov.redvital.donacion.domain.model.EstadoUnidad;
import co.gov.redvital.donacion.domain.model.EventoUnidad;
import co.gov.redvital.donacion.domain.model.RegistroAuditoria;
import co.gov.redvital.donacion.domain.model.ResultadoAuditoria;
import co.gov.redvital.donacion.domain.model.Unidad;
import co.gov.redvital.donacion.domain.exception.TransicionEstadoInvalidaException;
import co.gov.redvital.donacion.domain.exception.UnidadNoEncontradaException;
import co.gov.redvital.donacion.infrastructure.repository.EstadoUnidadRepository;
import co.gov.redvital.donacion.infrastructure.repository.EventoUnidadRepository;
import co.gov.redvital.donacion.infrastructure.repository.ObservacionOperativaRepository;
import co.gov.redvital.donacion.infrastructure.repository.RegistroAuditoriaRepository;
import co.gov.redvital.donacion.infrastructure.repository.TransicionValidaRepository;
import co.gov.redvital.donacion.infrastructure.repository.UnidadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Motor Único de Transiciones de Estado para la Unidad de Sangre (redvital-donacion-service).
 *
 * Cumple de forma estricta con las reglas de integridad y escenarios de calidad del DD V2.0 y SAD V2.0:
 * - IN-04: Transacción única atómica que actualiza unidad.estado_id e inserta evento_unidad + registro_auditoria.
 * - IN-05 / EC-04 / EC-36: Consulta el catálogo transicion_valida para verificar parejas (origen, destino, actor_tipo).
 * - RF-03 / DD Sec. 8.1 / Caso 0: Procesa el ALTA de la unidad en estado inicial 'captada' sin estado anterior (estadoAnteriorId = null).
 * - EC-02 / RI-02 / Anexo A: Garantiza la ausencia total de causas clínicas o resultados diagnósticos en mensajes y auditorías.
 * - EC-05 / RNF-09: Registra en registro_auditoria tanto los intentos permitidos como los rechazados/denegados.
 * - EC-12 / RNI-04: Mantiene la inmutabilidad del estado de la unidad ante intentos no autorizados o transiciones inválidas.
 * - EC-33: Atribuye la acción al tipo de actor 'sistema' o 'usuario' según corresponda.
 */
@Service
public class MotorTransicionEstadoUnidad {

    private static final Logger log = LoggerFactory.getLogger(MotorTransicionEstadoUnidad.class);
    private static final String ESTADO_CAPTADA_CODIGO = "captada";

    private final UnidadRepository unidadRepository;
    private final EstadoUnidadRepository estadoUnidadRepository;
    private final TransicionValidaRepository transicionValidaRepository;
    private final EventoUnidadRepository eventoUnidadRepository;
    private final RegistroAuditoriaRepository registroAuditoriaRepository;
    private final ObservacionOperativaRepository observacionOperativaRepository;

    public MotorTransicionEstadoUnidad(
            UnidadRepository unidadRepository,
            EstadoUnidadRepository estadoUnidadRepository,
            TransicionValidaRepository transicionValidaRepository,
            EventoUnidadRepository eventoUnidadRepository,
            RegistroAuditoriaRepository registroAuditoriaRepository,
            ObservacionOperativaRepository observacionOperativaRepository) {
        this.unidadRepository = unidadRepository;
        this.estadoUnidadRepository = estadoUnidadRepository;
        this.transicionValidaRepository = transicionValidaRepository;
        this.eventoUnidadRepository = eventoUnidadRepository;
        this.registroAuditoriaRepository = registroAuditoriaRepository;
        this.observacionOperativaRepository = observacionOperativaRepository;
    }

    /**
     * Solicitud de cambio de estado de una unidad de sangre existente.
     */
    public record TransicionRequest(
            UUID unidadId,
            String estadoDestinoCodigo,
            ActorTipo actorTipo,
            String actorId,
            String rol,
            UUID institucionId,
            String observacionCodigo, // Opcional, catálogo observacion_operativa (D-02)
            Boolean veredictoApta,     // Requerido únicamente para transiciones de tamizaje (RF-05)
            UUID nuevaInstitucionCustodiaId, // Opcional, para recepción de transferencia (IN-09)
            String correlacionId
    ) {}

    /**
     * Solicitud de Alta / Creación Inicial de una Unidad de Sangre derivada de una Donación (RF-03, Caso 0).
     */
    public record AltaUnidadRequest(
            UUID unidadId,                 // Opcional, si es nulo se genera un UUID
            UUID donacionId,               // Obligatorio, ID de la donación origen
            UUID componenteId,             // Obligatorio, componente hemático
            UUID grupoSanguineoId,         // Grupo sanguíneo inicial (ej. pendiente O+)
            UUID institucionCustodiaId,    // Banco de sangre custodia inicial
            LocalDate fechaVencimiento,     // Fecha de vencimiento calculada
            Integer volumenMl,             // Volumen en ml (opcional)
            ActorTipo actorTipo,           // USUARIO / SISTEMA
            String actorId,                // ID del operador o proceso
            String rol,                    // Rol del operador
            UUID institucionId,            // Institución donde ocurre la donación
            String observacionCodigo,      // Opcional, catálogo observacion_operativa
            String correlacionId           // Trazabilidad distribuida X-Correlacion-Id
    ) {}

    /**
     * Procesa el ALTA de una nueva unidad de sangre a través del motor único (RF-03 / Caso 0 / Caso 82).
     *
     * Inicializa la unidad en estado 'captada', asigna apta = NULL (hasta el tamizaje),
     * genera el evento inicial de creación con estadoAnteriorId = NULL y asienta la traza en registro_auditoria (ST2).
     *
     * @param request Datos de la solicitud de alta
     * @return Unidad creada y persistida de forma atómica.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Unidad procesarAltaUnidad(AltaUnidadRequest request) {
        Instant ahora = Instant.now();

        // 1. Obtener la entidad del catálogo estado_unidad para 'captada'
        EstadoUnidad estadoCaptada = estadoUnidadRepository.findByCodigo(ESTADO_CAPTADA_CODIGO)
                .orElseThrow(() -> new IllegalStateException("El estado inicial 'captada' no existe en el catálogo estado_unidad"));

        UUID unidadId = request.unidadId() != null ? request.unidadId() : UUID.randomUUID();

        // 2. Instanciar y persistir la nueva entidad Unidad (RF-03)
        Unidad unidad = new Unidad();
        unidad.setId(unidadId);
        unidad.setDonacionId(request.donacionId());
        unidad.setComponenteId(request.componenteId());
        unidad.setGrupoSanguineoId(request.grupoSanguineoId());
        unidad.setInstitucionCustodiaId(request.institucionCustodiaId());
        unidad.setEstadoId(estadoCaptada.getId());
        unidad.setApta(null); // NULL hasta el tamizaje analítico (IN-08)
        unidad.setFechaVencimiento(request.fechaVencimiento());
        unidad.setVolumenMl(request.volumenMl());
        unidad.setCreadoEn(ahora);
        unidad.setActualizadoEn(ahora);

        unidadRepository.save(unidad);

        // 3. Resolver observación del catálogo si fue proporcionada
        UUID observacionId = null;
        if (request.observacionCodigo() != null && !request.observacionCodigo().isBlank()) {
            observacionId = observacionOperativaRepository.findByCodigoAndVigenteTrue(request.observacionCodigo())
                    .map(o -> o.getId())
                    .orElse(null);
        }

        // 4. Crear y persistir el EventoUnidad inicial de creación (estadoAnteriorId = NULL)
        EventoUnidad eventoInicial = new EventoUnidad();
        eventoInicial.setId(UUID.randomUUID());
        eventoInicial.setUnidadId(unidadId);
        eventoInicial.setEstadoAnteriorId(null); // NULL por ser evento cero / creación (RF-03)
        eventoInicial.setEstadoNuevoId(estadoCaptada.getId());
        eventoInicial.setActorTipo(request.actorTipo());
        eventoInicial.setActorId(request.actorId());
        eventoInicial.setInstitucionId(request.institucionId());
        eventoInicial.setObservacionId(observacionId);
        eventoInicial.setCorrelacionId(request.correlacionId());
        eventoInicial.setOcurridoEn(ahora);

        eventoUnidadRepository.save(eventoInicial);

        // 5. Asentar traza inmutable en registro_auditoria (ST2, EC-05, RNF-09)
        registrarAuditoriaAltaPermitida(unidad, estadoCaptada, request, ahora);

        log.info("Alta de unidad procesada exitosamente por el motor. Unidad: {}, Donación: {}, Componente: {}, Estado: captada, Correlación: {}",
                unidadId, request.donacionId(), request.componenteId(), request.correlacionId());

        return unidad;
    }

    /**
     * Punto de entrada único para ejecutar un cambio de estado en una unidad existente.
     *
     * @param request Datos de la transición solicitada
     * @return EventoUnidad generado si la transición fue exitosa.
     * @throws TransicionEstadoInvalidaException si la transición no está en el catálogo transicion_valida (HTTP 409).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EventoUnidad procesarTransicion(TransicionRequest request) {
        Instant ahora = Instant.now();

        // 1. Cargar la unidad (bloqueo pesimista de fila para evitar condiciones de carrera en inventario/estado)
        Unidad unidad = unidadRepository.findByIdForUpdate(request.unidadId())
                .orElseThrow(() -> new UnidadNoEncontradaException(request.unidadId(), request.correlacionId()));

        UUID estadoOrigenId = unidad.getEstadoId();
        EstadoUnidad estadoOrigen = estadoUnidadRepository.findById(estadoOrigenId)
                .orElseThrow(() -> new IllegalStateException("Estado origen no existe en catálogo: " + estadoOrigenId));

        EstadoUnidad estadoDestino = estadoUnidadRepository.findByCodigo(request.estadoDestinoCodigo())
                .orElseThrow(() -> new TransicionEstadoInvalidaException(
                        "El estado destino pretendido no existe en catálogo: " + request.estadoDestinoCodigo(),
                        estadoOrigen.getCodigo(),
                        request.estadoDestinoCodigo(),
                        request.correlacionId()
                ));

        UUID estadoDestinoId = estadoDestino.getId();

        // 2. Consulta al catálogo estricto 'transicion_valida' (Matriz cerrada de 13 transiciones)
        boolean esTransicionValida = transicionValidaRepository.existsByEstadoOrigenIdAndEstadoDestinoIdAndActorTipo(
                estadoOrigenId,
                estadoDestinoId,
                request.actorTipo()
        );

        // 3. RECHAZO: Transición no listada en el catálogo
        if (!esTransicionValida) {
            // A. Registrar el intento DENEGADO en la bitácora inmutable de auditoría (ST2, EC-05, RNF-09)
            registrarAuditoriaIntentoDenegado(unidad, estadoOrigen, estadoDestino, request, ahora);

            log.warn("Transición rechazada por catálogo transicion_valida. Unidad: {}, Origen: {}, Destino: {}, ActorTipo: {}, Correlación: {}",
                    unidad.getId(), estadoOrigen.getCodigo(), estadoDestino.getCodigo(), request.actorTipo(), request.correlacionId());

            // B. Lanzar excepción de conflicto de estado (HTTP 409 conflicto-de-estado, RFC 9457)
            // EL ESTADO DE LA UNIDAD PERMANECE INALTERADO (unidad.estado_id NO SE MODIFICA)
            throw new TransicionEstadoInvalidaException(
                    String.format("La transición del estado '%s' al estado '%s' para el actor '%s' no está permitida por el catálogo normativo.",
                            estadoOrigen.getCodigo(), estadoDestino.getCodigo(), request.actorTipo().codigo()),
                    estadoOrigen.getCodigo(),
                    estadoDestino.getCodigo(),
                    request.correlacionId()
            );
        }

        // 4. ACEPTACIÓN: Transición válida según el catálogo -> Ejecutar cambio de estado de forma atómica (IN-04)
        
        // A. Actualizar estado de la unidad
        unidad.setEstadoId(estadoDestinoId);
        unidad.setActualizadoEn(ahora);

        // B. Regla IN-08: Asignación de aptitud en tamizaje (transiciones 3 y 4)
        if ("en_tamizaje".equals(estadoOrigen.getCodigo())) {
            if ("disponible".equals(estadoDestino.getCodigo())) {
                unidad.setApta(true);
            } else if ("no_apta".equals(estadoDestino.getCodigo())) {
                unidad.setApta(false); // Único dato guardado, NINGUNA causa clínica (EC-02, RI-02)
            }
        }

        // C. Actualización de custodia en recepción de transferencia (transición 6)
        if ("reservada".equals(estadoOrigen.getCodigo()) && "disponible".equals(estadoDestino.getCodigo()) && request.nuevaInstitucionCustodiaId() != null) {
            unidad.setInstitucionCustodiaId(request.nuevaInstitucionCustodiaId());
        }

        unidadRepository.save(unidad);

        // D. Insertar evento de historial inmutable (evento_unidad)
        UUID observacionId = null;
        if (request.observacionCodigo() != null && !request.observacionCodigo().isBlank()) {
            observacionId = observacionOperativaRepository.findByCodigoAndVigenteTrue(request.observacionCodigo())
                    .map(o -> o.getId())
                    .orElse(null);
        }

        EventoUnidad evento = new EventoUnidad();
        evento.setId(UUID.randomUUID());
        evento.setUnidadId(unidad.getId());
        evento.setEstadoAnteriorId(estadoOrigenId);
        evento.setEstadoNuevoId(estadoDestinoId);
        evento.setActorTipo(request.actorTipo());
        evento.setActorId(request.actorId());
        evento.setInstitucionId(request.institucionId());
        evento.setObservacionId(observacionId);
        evento.setCorrelacionId(request.correlacionId());
        evento.setOcurridoEn(ahora);

        eventoUnidadRepository.save(evento);

        // E. Registrar auditoría PERMITIDA (registro_auditoria, ST2)
        registrarAuditoriaIntentoPermitido(unidad, estadoOrigen, estadoDestino, request, ahora);

        log.info("Transición de estado ejecutada exitosamente. Unidad: {}, De: {} A: {}, Actor: {}, Correlación: {}",
                unidad.getId(), estadoOrigen.getCodigo(), estadoDestino.getCodigo(), request.actorId(), request.correlacionId());

        return evento;
    }

    /**
     * Registra en la bitácora inmutable de auditoría (ST2) un ALTA de unidad autorizada.
     */
    private void registrarAuditoriaAltaPermitida(
            Unidad unidad,
            EstadoUnidad estadoCaptada,
            AltaUnidadRequest request,
            Instant ahora) {

        RegistroAuditoria auditoria = new RegistroAuditoria();
        auditoria.setId(UUID.randomUUID());
        auditoria.setActorTipo(request.actorTipo());
        auditoria.setActorId(request.actorId());
        auditoria.setRol(request.rol());
        auditoria.setJurisdiccionSolicitada(request.institucionId() != null ? "institucion:" + request.institucionId() : null);
        auditoria.setOperacion("ALTA_UNIDAD_DONACION");
        auditoria.setRecursoTipo("unidad");
        auditoria.setRecursoId(unidad.getId());
        auditoria.setResultado(ResultadoAuditoria.PERMITIDO);
        auditoria.setCorrelacionId(request.correlacionId());
        auditoria.setOcurridoEn(ahora);

        auditoria.setDetalles(Map.of(
                "estado_inicial", estadoCaptada.getCodigo(),
                "donacion_id", request.donacionId().toString(),
                "componente_id", request.componenteId().toString()
        ));

        registroAuditoriaRepository.save(auditoria);
    }

    /**
     * Registra en la bitácora inmutable de auditoría (ST2) un intento de transición DENEGADO por el catálogo.
     */
    private void registrarAuditoriaIntentoDenegado(
            Unidad unidad,
            EstadoUnidad estadoOrigen,
            EstadoUnidad estadoDestino,
            TransicionRequest request,
            Instant ahora) {

        RegistroAuditoria auditoria = new RegistroAuditoria();
        auditoria.setId(UUID.randomUUID());
        auditoria.setActorTipo(request.actorTipo());
        auditoria.setActorId(request.actorId());
        auditoria.setRol(request.rol());
        auditoria.setJurisdiccionSolicitada(request.institucionId() != null ? "institucion:" + request.institucionId() : null);
        auditoria.setOperacion("TRANSICION_ESTADO_UNIDAD");
        auditoria.setRecursoTipo("unidad");
        auditoria.setRecursoId(unidad.getId());
        auditoria.setResultado(ResultadoAuditoria.DENEGADO); // 'denegado'
        auditoria.setCorrelacionId(request.correlacionId());
        auditoria.setOcurridoEn(ahora);

        auditoria.setDetalles(Map.of(
                "estado_actual", estadoOrigen.getCodigo(),
                "estado_pretendido", estadoDestino.getCodigo(),
                "motivo_rechazo", "Transición no permitida en catálogo transicion_valida"
        ));

        registroAuditoriaRepository.save(auditoria);
    }

    /**
     * Registra en la bitácora inmutable de auditoría (ST2) un cambio de estado PERMITIDO y exitoso.
     */
    private void registrarAuditoriaIntentoPermitido(
            Unidad unidad,
            EstadoUnidad estadoOrigen,
            EstadoUnidad estadoDestino,
            TransicionRequest request,
            Instant ahora) {

        RegistroAuditoria auditoria = new RegistroAuditoria();
        auditoria.setId(UUID.randomUUID());
        auditoria.setActorTipo(request.actorTipo());
        auditoria.setActorId(request.actorId());
        auditoria.setRol(request.rol());
        auditoria.setJurisdiccionSolicitada(request.institucionId() != null ? "institucion:" + request.institucionId() : null);
        auditoria.setOperacion("TRANSICION_ESTADO_UNIDAD");
        auditoria.setRecursoTipo("unidad");
        auditoria.setRecursoId(unidad.getId());
        auditoria.setResultado(ResultadoAuditoria.PERMITIDO); // 'permitido'
        auditoria.setCorrelacionId(request.correlacionId());
        auditoria.setOcurridoEn(ahora);

        auditoria.setDetalles(Map.of(
                "estado_anterior", estadoOrigen.getCodigo(),
                "estado_nuevo", estadoDestino.getCodigo()
        ));

        registroAuditoriaRepository.save(auditoria);
    }
}
