package co.gov.redvital.donacion.domain.service;

import co.gov.redvital.donacion.domain.model.ActorTipo;
import co.gov.redvital.donacion.domain.model.RegistroAuditoria;
import co.gov.redvital.donacion.domain.model.ResultadoAuditoria;
import co.gov.redvital.donacion.infrastructure.repository.RegistroAuditoriaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * =============================================================================
 * REDVITAL - Plataforma Nacional de Gestión de Bancos de Sangre
 * Servicio de Donación (redvital-donacion-service - Java 25 / Spring Boot 4.1)
 * =============================================================================
 *
 * SERVICIO DE REGISTRO DE AUDITORÍA DE DESPACHO Y DESECHO (ST2 / RNF-09 / ADR-016)
 *
 * Registra formalmente en la serie inmutable de auditoría (tabla 'registro_auditoria'
 * de db_donacion) todas las acciones sensibles e intentos de las operaciones de
 * despacho para transfusión y disposición final (desecho físico):
 *
 * - EC-05 / RNF-09: No repudio y trazabilidad inmutable de acciones en la bitácora ST2.
 * - EC-01 / EC-05: Registro del 100% de intentos, tanto PERMITIDOS como DENEGADOS.
 * - EC-19 / EC-41 / DD Sec. 12.1: Registro de fallos por confirmación no coincidente en despacho/desecho.
 * - IN-21 / EC-39: Registro de denegaciones por violación de custodia institucional.
 * - IN-22 / EC-12: Registro de denegaciones por intento de despacho de unidades no aptas o vencidas.
 * - EC-02 / RI-02 / Anexo A: AUSENCIA TOTAL DE CAUSAS CLÍNICAS, DIAGNÓSTICOS O DATOS PERSONALES
 *   en el campo de detalles JSONB.
 * - EC-20: Trazabilidad distribuida mediante propagación del identificador 'correlacion_id'.
 */
@Service
public class RegistroAuditoriaDespachoDesechoService {

    private static final Logger log = LoggerFactory.getLogger(RegistroAuditoriaDespachoDesechoService.class);

    public static final String OPERACION_DESPACHO = "DESPACHO_UNIDAD";
    public static final String OPERACION_DISPOSICION_FINAL = "DISPOSICION_FINAL_UNIDAD";
    public static final String RECURSO_TIPO_UNIDAD = "unidad";

    private final RegistroAuditoriaRepository registroAuditoriaRepository;

    public RegistroAuditoriaDespachoDesechoService(RegistroAuditoriaRepository registroAuditoriaRepository) {
        this.registroAuditoriaRepository = registroAuditoriaRepository;
    }

    // =========================================================================
    // 1. AUDITORÍA DE DESPACHO DE UNIDAD (POST /v1/unidades/{id}/despacho)
    // =========================================================================

    /**
     * Registra en la bitácora ST2 un despacho PERMITIDO y exitoso.
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public void registrarDespachoExitoso(
            UUID unidadId,
            UUID institucionCustodiaId,
            String actorId,
            String rol,
            String correlacionId,
            Instant ocurridoEn
    ) {
        Map<String, Object> detalles = new HashMap<>();
        detalles.put("estado_anterior", "reservada");
        detalles.put("estado_nuevo", "despachada");
        detalles.put("institucion_custodia_id", institucionCustodiaId.toString());
        detalles.put("confirmacion_verificada", true);

        guardarRegistroAuditoria(
                actorId,
                rol,
                institucionCustodiaId,
                OPERACION_DESPACHO,
                unidadId,
                ResultadoAuditoria.PERMITIDO,
                correlacionId,
                detalles,
                ocurridoEn
        );

        log.info("Auditoría ST2 registrada: Despacho PERMITIDO. Unidad: {}, Actor: {}, Correlación: {}",
                unidadId, actorId, correlacionId);
    }

    /**
     * Registra un intento DENEGADO de despacho por confirmación no coincidente (EC-19 / EC-41).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void registrarDespachoDenegadoConfirmacionInvalida(
            UUID unidadId,
            String confirmacionRecibida,
            String actorId,
            String rol,
            UUID institucionId,
            String correlacionId,
            Instant ocurridoEn
    ) {
        Map<String, Object> detalles = new HashMap<>();
        detalles.put("motivo_rechazo", "El campo confirmacion es obligatorio y no coincide con el identificador de la unidad");
        detalles.put("confirmacion_esperada", unidadId.toString());
        detalles.put("confirmacion_recibida", confirmacionRecibida != null ? confirmacionRecibida : "null");

        guardarRegistroAuditoria(
                actorId,
                rol,
                institucionId,
                OPERACION_DESPACHO,
                unidadId,
                ResultadoAuditoria.DENEGADO,
                correlacionId,
                detalles,
                ocurridoEn
        );

        log.warn("Auditoría ST2 registrada: Despacho DENEGADO (Confirmación Inválida). Unidad: {}, Actor: {}, Correlación: {}",
                unidadId, actorId, correlacionId);
    }

    /**
     * Registra un intento DENEGADO de despacho por violación de la regla de custodia (IN-21 / EC-39).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void registrarDespachoDenegadoInfraccionCustodiaIN21(
            UUID unidadId,
            UUID institucionCustodiaUnidad,
            UUID institucionOperador,
            String actorId,
            String rol,
            String correlacionId,
            Instant ocurridoEn
    ) {
        Map<String, Object> detalles = new HashMap<>();
        detalles.put("motivo_rechazo", "Infracción de regla IN-21: La unidad no se encuentra bajo la custodia de la institución del operador");
        detalles.put("institucion_custodia_esperada", institucionCustodiaUnidad.toString());
        detalles.put("institucion_operador_recibida", institucionOperador.toString());

        guardarRegistroAuditoria(
                actorId,
                rol,
                institucionOperador,
                OPERACION_DESPACHO,
                unidadId,
                ResultadoAuditoria.DENEGADO,
                correlacionId,
                detalles,
                ocurridoEn
        );

        log.warn("Auditoría ST2 registrada: Despacho DENEGADO (Infracción IN-21 Custodia). Unidad: {}, Actor: {}, Correlación: {}",
                unidadId, actorId, correlacionId);
    }

    /**
     * Registra un intento DENEGADO de despacho por unidad no apta o vencida (IN-22 / EC-12).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void registrarDespachoDenegadoUnidadNoAptaOVencidaIN22(
            UUID unidadId,
            Boolean apta,
            LocalDate fechaVencimiento,
            String estadoActualCodigo,
            String actorId,
            String rol,
            UUID institucionId,
            String correlacionId,
            Instant ocurridoEn
    ) {
        Map<String, Object> detalles = new HashMap<>();
        detalles.put("motivo_rechazo", "Infracción de regla IN-22: Intento de despacho sobre unidad no apta o con fecha de vencimiento expirada");
        detalles.put("estado_actual", estadoActualCodigo);
        detalles.put("apta", apta);
        detalles.put("fecha_vencimiento", fechaVencimiento != null ? fechaVencimiento.toString() : "null");

        guardarRegistroAuditoria(
                actorId,
                rol,
                institucionId,
                OPERACION_DESPACHO,
                unidadId,
                ResultadoAuditoria.DENEGADO,
                correlacionId,
                detalles,
                ocurridoEn
        );

        log.warn("Auditoría ST2 registrada: Despacho DENEGADO (Infracción IN-22 Aptitud/Vencimiento). Unidad: {}, Actor: {}, Correlación: {}",
                unidadId, actorId, correlacionId);
    }

    // =========================================================================
    // 2. AUDITORÍA DE DISPOSICIÓN FINAL (POST /v1/unidades/{id}/disposicion-final)
    // =========================================================================

    /**
     * Registra en la bitácora ST2 una disposición final PERMITIDA y exitosa.
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public void registrarDisposicionFinalExitosa(
            UUID unidadId,
            String estadoAnteriorCodigo,
            String observacionCodigo,
            String actorId,
            String rol,
            UUID institucionId,
            String correlacionId,
            Instant ocurridoEn
    ) {
        Map<String, Object> detalles = new HashMap<>();
        detalles.put("estado_anterior", estadoAnteriorCodigo);
        detalles.put("estado_nuevo", "desechada");
        detalles.put("observacion_codigo", observacionCodigo != null ? observacionCodigo : "ninguna");
        detalles.put("confirmacion_verificada", true);

        guardarRegistroAuditoria(
                actorId,
                rol,
                institucionId,
                OPERACION_DISPOSICION_FINAL,
                unidadId,
                ResultadoAuditoria.PERMITIDO,
                correlacionId,
                detalles,
                ocurridoEn
        );

        log.info("Auditoría ST2 registrada: Disposición Final PERMITIDA. Unidad: {}, Actor: {}, Correlación: {}",
                unidadId, actorId, correlacionId);
    }

    /**
     * Registra un intento DENEGADO de disposición final por confirmación no coincidente (EC-19 / EC-41).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void registrarDisposicionFinalDenegadaConfirmacionInvalida(
            UUID unidadId,
            String confirmacionRecibida,
            String actorId,
            String rol,
            UUID institucionId,
            String correlacionId,
            Instant ocurridoEn
    ) {
        Map<String, Object> detalles = new HashMap<>();
        detalles.put("motivo_rechazo", "El campo confirmacion es obligatorio y no coincide con el identificador de la unidad para disposición final");
        detalles.put("confirmacion_esperada", unidadId.toString());
        detalles.put("confirmacion_recibida", confirmacionRecibida != null ? confirmacionRecibida : "null");

        guardarRegistroAuditoria(
                actorId,
                rol,
                institucionId,
                OPERACION_DISPOSICION_FINAL,
                unidadId,
                ResultadoAuditoria.DENEGADO,
                correlacionId,
                detalles,
                ocurridoEn
        );

        log.warn("Auditoría ST2 registrada: Disposición Final DENEGADA (Confirmación Inválida). Unidad: {}, Actor: {}, Correlación: {}",
                unidadId, actorId, correlacionId);
    }

    /**
     * Registra un intento DENEGADO de disposición final por estado de origen no permitido (EC-04 / Tabla 19).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void registrarDisposicionFinalDenegadaEstadoInvalido(
            UUID unidadId,
            String estadoOrigenCodigo,
            String actorId,
            String rol,
            UUID institucionId,
            String correlacionId,
            Instant ocurridoEn
    ) {
        Map<String, Object> detalles = new HashMap<>();
        detalles.put("motivo_rechazo", "Transición no admitida en catálogo transicion_valida. Solo se admite disposición final desde estados no_apta o vencida");
        detalles.put("estado_actual", estadoOrigenCodigo);
        detalles.put("estado_pretendido", "desechada");

        guardarRegistroAuditoria(
                actorId,
                rol,
                institucionId,
                OPERACION_DISPOSICION_FINAL,
                unidadId,
                ResultadoAuditoria.DENEGADO,
                correlacionId,
                detalles,
                ocurridoEn
        );

        log.warn("Auditoría ST2 registrada: Disposición Final DENEGADA (Estado Origen Inválido: {}). Unidad: {}, Actor: {}, Correlación: {}",
                estadoOrigenCodigo, unidadId, actorId, correlacionId);
    }

    // =========================================================================
    // MÉTODOS PRIVADOS DE PERSISTENCIA INMUTABLE (ST2)
    // =========================================================================

    private void guardarRegistroAuditoria(
            String actorId,
            String rol,
            UUID institucionId,
            String operacion,
            UUID recursoId,
            ResultadoAuditoria resultado,
            String correlacionId,
            Map<String, Object> detalles,
            Instant ocurridoEn
    ) {
        RegistroAuditoria auditoria = new RegistroAuditoria();
        auditoria.setId(UUID.randomUUID());
        auditoria.setActorTipo(ActorTipo.USUARIO);
        auditoria.setActorId(actorId);
        auditoria.setRol(rol != null ? rol : "operador");
        auditoria.setJurisdiccionSolicitada(institucionId != null ? "institucion:" + institucionId : null);
        auditoria.setOperacion(operacion);
        auditoria.setRecursoTipo(RECURSO_TIPO_UNIDAD);
        auditoria.setRecursoId(recursoId);
        auditoria.setResultado(resultado);
        auditoria.setCorrelacionId(correlacionId);
        auditoria.setDetalles(detalles);
        auditoria.setOcurridoEn(ocurridoEn != null ? ocurridoEn : Instant.now());

        registroAuditoriaRepository.save(auditoria);
    }
}
