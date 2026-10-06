package co.gov.redvital.donacion.infrastructure.scheduler;

import co.gov.redvital.donacion.domain.model.ActorTipo;
import co.gov.redvital.donacion.domain.service.MotorTransicionEstadoUnidad;
import co.gov.redvital.donacion.domain.service.MotorTransicionEstadoUnidad.TransicionRequest;
import co.gov.redvital.donacion.infrastructure.repository.UnidadRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

/**
 * =============================================================================
 * REDVITAL - Plataforma Nacional de Gestión de Bancos de Sangre
 * Servicio de Donación (redvital-donacion-service - Java 25 / Spring Boot 4.1)
 * =============================================================================
 *
 * PROCESO PROGRAMADO DE VENCIMIENTO AUTOMÁTICO CON ACTOR SISTEMA Y CORRELACIÓN POR CICLO (DS-06)
 *
 * Cumple de forma estricta con las reglas normativas y escenarios de calidad:
 * - Actor Sistema con Nombre de Proceso (EC-33 / DD Sec 10.2): La acción de vencimiento
 *   se atribuye explícitamente a ActorTipo.SISTEMA utilizando el nombre oficial del proceso
 *   'SISTEMA_VENCIMIENTO_AUTOMATICO' como actor_id y 'sistema' como rol.
 * - Identificador de Correlación Unificado por Ciclo (EC-20): Genera un único 'correlacionCicloId'
 *   por cada iteración del scheduler de 5 minutos, inyectándolo en SLF4J MDC para estructurar
 *   todos los registros de aplicación y propagándolo a cada TransicionRequest del lote.
 * - Ciclo de Ejecución de 5 Minutos (Resolución RDS-13): Sincroniza la discrepancia entre
 *   DD e Infraestructura fijando el cron programado en '0 *\/5 * * * *'.
 * - Bloqueo Consultivo PostgreSQL (DS-06): Ejecuta pg_try_advisory_xact_lock para prevenir
 *   ejecuciones concurrentes entre múltiples réplicas/pods en Kubernetes (EKS).
 * - Zona Horaria UTC en Contenedor (SAD V2.0 / Infraestructura): Fuerza la zona horaria UTC
 *   en el arranque mediante TimeZone.setDefault().
 */
@Component
public class VencimientoUnidadesScheduledJobDS06 {

    private static final Logger log = LoggerFactory.getLogger(VencimientoUnidadesScheduledJobDS06.class);

    // Clave numérica fija de 64 bits para el bloqueo consultivo en db_donacion (DS-06)
    private static final long LOCK_ID_VENCIMIENTO_DS06 = 0x56454E434155544FL; // "VENCAUT" en Hexadecimal

    private static final String ESTADO_DESTINO_VENCIDA = "vencida";
    private static final String NOMBRE_PROCESO_ACTOR_SISTEMA = "SISTEMA_VENCIMIENTO_AUTOMATICO";
    private static final String ROL_SISTEMA = "sistema";
    private static final String OBSERVACION_CATALOGO_VENCIMIENTO = "vencimiento_automatico_cron";

    private final MotorTransicionEstadoUnidad motorTransicionService;
    private final UnidadRepository unidadRepository;
    private final JdbcTemplate jdbcTemplate;

    public VencimientoUnidadesScheduledJobDS06(
            MotorTransicionEstadoUnidad motorTransicionService,
            UnidadRepository unidadRepository,
            JdbcTemplate jdbcTemplate) {
        this.motorTransicionService = motorTransicionService;
        this.unidadRepository = unidadRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Configuración del contenedor Java en el arranque (SAD V2.0):
     * Fuerza la zona horaria predeterminada a UTC.
     */
    @PostConstruct
    public void inicializarZonaHorariaUTC() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        log.info("Inicializado scheduler de vencimiento automático. Zona horaria del contenedor forzada a UTC: {}", ZoneOffset.UTC);
    }

    /**
     * Tarea programada ejecutada automáticamente cada 5 minutos (cron: "0 *\/5 * * * *").
     *
     * Genera un identificador de correlación único POR CICLO y lo inyecta en SLF4J MDC.
     */
    @Scheduled(cron = "0 */5 * * * *")
    public void ejecutarCicloVencimientoAutomatico() {
        // Generación del identificador de correlación unificado POR CICLO (EC-20)
        String correlacionCicloId = "CORR-CICLO-VENC-" + UUID.randomUUID().toString().substring(0, 8);

        try {
            // Inyección en MDC para que todos los logs del ciclo incluyan [correlacion_id=...]
            MDC.put("correlacion_id", correlacionCicloId);

            log.info("Iniciando ciclo programado de vencimiento automático de unidades (Ciclo 5 min). Correlación Ciclo: {}", correlacionCicloId);

            procesarLoteVencimientoConBloqueoConsultivo(correlacionCicloId);

        } finally {
            // Limpieza del contexto MDC para evitar fugas en el pool de hilos
            MDC.remove("correlacion_id");
        }
    }

    /**
     * Método transaccional que adquiere el bloqueo consultivo (DS-06)
     * y ejecuta las transiciones a través del Motor Único.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void procesarLoteVencimientoConBloqueoConsultivo(String correlacionCicloId) {
        // 1. ADQUISICIÓN NON-BLOCKING DE BLOQUEO CONSULTIVO EN POSTGRESQL (DS-06)
        Boolean candadoObtenido = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)",
                Boolean.class,
                LOCK_ID_VENCIMIENTO_DS06
        );

        if (Boolean.FALSE.equals(candadoObtenido)) {
            log.debug("Bloqueo consultivo (DS-06) ocupado por otra instancia. Omitiendo ejecución en este pod.");
            return;
        }

        log.info("Bloqueo consultivo (DS-06) adquirido exitosamente para el ciclo {}. Procesando unidades...", correlacionCicloId);

        // 2. OBTENCIÓN DE FECHA ACTUAL EN UTC ESTRICTO
        LocalDate fechaActualUTC = LocalDate.now(ZoneOffset.UTC);

        // 3. CONSULTA DE UNIDADES EXPIRADAS
        List<UUID> unidadesExpiradasIds = unidadRepository.findIdsUnidadesExpiradasParaVencimiento(fechaActualUTC);

        if (unidadesExpiradasIds.isEmpty()) {
            log.info("Ciclo de vencimiento completado sin novedades para {}. No se encontraron unidades expiradas al {}.",
                    correlacionCicloId, fechaActualUTC);
            return;
        }

        log.info("Se identificaron {} unidades expiradas para procesar vencimiento automático en el ciclo {} (Fecha UTC: {}).",
                unidadesExpiradasIds.size(), correlacionCicloId, fechaActualUTC);

        int procesadasExitosas = 0;
        int erroresProcesamiento = 0;

        // 4. PROCESAMIENTO CON ACTOR SISTEMA Y CORRELACIÓN DE CICLO
        for (UUID unidadId : unidadesExpiradasIds) {
            try {
                // Solicitud de transición con ActorTipo.SISTEMA, el nombre oficial del proceso y la correlación del ciclo
                TransicionRequest request = new TransicionRequest(
                        unidadId,
                        ESTADO_DESTINO_VENCIDA,
                        ActorTipo.SISTEMA, // ActorTipo = SISTEMA (EC-33)
                        NOMBRE_PROCESO_ACTOR_SISTEMA, // actor_id = 'SISTEMA_VENCIMIENTO_AUTOMATICO'
                        ROL_SISTEMA, // rol = 'sistema'
                        null, // Sin institución para actor sistema
                        OBSERVACION_CATALOGO_VENCIMIENTO, // Catálogo observacion_operativa
                        null,
                        null,
                        correlacionCicloId // Mismo identificador de correlación para todo el lote del ciclo (EC-20)
                );

                // Delegación atómica al Motor Único de Transiciones de Estado
                motorTransicionService.procesarTransicion(request);
                procesadasExitosas++;

            } catch (Exception ex) {
                erroresProcesamiento++;
                log.error("Error al procesar vencimiento automático de la unidad {}. Ciclo: {}. Detalle: {}",
                        unidadId, correlacionCicloId, ex.getMessage(), ex);
            }
        }

        log.info("Ciclo de vencimiento {} finalizado. Exitosas: {}, Fallidas: {}, Fecha UTC: {}",
                correlacionCicloId, procesadasExitosas, erroresProcesamiento, fechaActualUTC);
    }
}
