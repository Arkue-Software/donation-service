package co.gov.redvital.donacion.web.controller;

import co.gov.redvital.donacion.domain.model.ActorTipo;
import co.gov.redvital.donacion.domain.model.RegistroAuditoria;
import co.gov.redvital.donacion.domain.model.ResultadoAuditoria;
import co.gov.redvital.donacion.domain.service.ConsultaInventarioDisponibleDS04.EstadoUnidadEntity;
import co.gov.redvital.donacion.domain.service.ConsultaInventarioDisponibleDS04.UnidadEntity;
import co.gov.redvital.donacion.infrastructure.repository.RegistroAuditoriaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * =============================================================================
 * REDVITAL - Plataforma Nacional de Gestión de Bancos de Sangre
 * Servicio de Donación (redvital-donacion-service - Java 25 / Spring Boot 4.1)
 * =============================================================================
 *
 * RESPUESTA INDISTINGUIBLE ANTE INSTITUCIÓN AJENA Y REGISTRO DE AUDITORÍA (ST2)
 *
 * Cumple de forma estricta con los escenarios de calidad y reglas normativas:
 * - EC-01 / RNI-03 / DD Sec. 12.1: Control de acceso por jurisdicción. La respuesta ante una
 *   solicitud de inventario/existencias para una institución ajena o fuera de alcance es
 *   DELIBERADAMENTE INDISTINGUIBLE del caso en que la institución no posee existencias o no existe.
 *   No confirma ni niega la existencia de recursos ni instituciones ajenas (evita enumeración EC-06).
 * - EC-05 / RNF-09 / ST2 / ADR-016: Registro obligatorio e inmutable en la bitácora 'registro_auditoria'
 *   de todo intento de acceso denegado fuera de jurisdicción ('resultado = denegado').
 * - DS-04 / IN-22 / EC-12: Proyección estricta sobre existencias con cuenta_disponible = true,
 *   apta = true y fecha_vencimiento >= CURRENT_DATE.
 * - EC-02 / RI-02 / Anexo A: Ausencia total de causa clínica, datos personales o contenido del recurso
 *   en respuestas, trazas y registros de auditoría.
 */
public class RespuestaIndistinguibleInstitucionAjenaDS04 {

    // =========================================================================
    // 1. DTOs Y CONTRATOS REST
    // =========================================================================

    public record ExistenciasComponenteTipoSanguineoDto(
            UUID institucionId,
            UUID componenteId,
            UUID grupoSanguineoId,
            long cantidadDisponible,
            LocalDate fechaVencimientoMasProxima
    ) {}

    // =========================================================================
    // 2. REPOSITORIO JPA (PROYECCIÓN DS-04)
    // =========================================================================

    @Repository
    public interface ExistenciasInstitucionRepository extends JpaRepository<UnidadEntity, UUID> {

        @Query("""
            SELECT new co.gov.redvital.donacion.web.controller.RespuestaIndistinguibleInstitucionAjenaDS04$ExistenciasComponenteTipoSanguineoDto(
                u.institucionCustodiaId,
                u.componenteId,
                u.grupoSanguineoId,
                COUNT(u.id),
                MIN(u.fechaVencimiento)
            )
            FROM UnidadEntity u
            JOIN EstadoUnidadEntity e ON u.estadoId = e.id
            WHERE u.institucionCustodiaId = :institucionId
              AND e.cuentaDisponible = true
              AND u.apta = true
              AND u.fechaVencimiento >= :fechaActual
              AND (:componenteId IS NULL OR u.componenteId = :componenteId)
              AND (:grupoSanguineoId IS NULL OR u.grupoSanguineoId = :grupoSanguineoId)
            GROUP BY u.institucionCustodiaId, u.componenteId, u.grupoSanguineoId
            ORDER BY u.componenteId ASC, u.grupoSanguineoId ASC
            """)
        List<ExistenciasComponenteTipoSanguineoDto> consultarExistenciasPorInstitucionYFiltros(
                @Param("institucionId") UUID institucionId,
                @Param("fechaActual") LocalDate fechaActual,
                @Param("componenteId") UUID componenteId,
                @Param("grupoSanguineoId") UUID grupoSanguineoId
        );
    }

    // =========================================================================
    // 3. SERVICIO TRANSACCIONAL DE INVENTARIO Y REGISTRO DE AUDITORÍA
    // =========================================================================

    @Service
    public static class ExistenciasIndistinguibleService {

        private static final Logger log = LoggerFactory.getLogger(ExistenciasIndistinguibleService.class);

        private final ExistenciasInstitucionRepository existenciasRepository;
        private final RegistroAuditoriaRepository registroAuditoriaRepository;

        public ExistenciasIndistinguibleService(
                ExistenciasInstitucionRepository existenciasRepository,
                RegistroAuditoriaRepository registroAuditoriaRepository) {
            this.existenciasRepository = existenciasRepository;
            this.registroAuditoriaRepository = registroAuditoriaRepository;
        }

        /**
         * Ejecuta la consulta de existencias autorizada.
         */
        @Transactional(readOnly = true)
        public List<ExistenciasComponenteTipoSanguineoDto> consultarExistenciasAutorizadas(
                UUID institucionId,
                UUID componenteId,
                UUID grupoSanguineoId
        ) {
            LocalDate fechaActual = LocalDate.now();
            return existenciasRepository.consultarExistenciasPorInventarioYFiltros(institucionId, fechaActual, componenteId, grupoSanguineoId);
        }

        /**
         * Registra en la bitácora inmutable de auditoría (ST2, EC-05, RNF-09) un intento de acceso DENEGADO
         * por consulta fuera de la jurisdicción de la institución del token.
         */
        @Transactional
        public void registrarIntentoAccesoDenegadoJurisdiccion(
                String actorId,
                String rol,
                UUID institucionSolicitadaId,
                String correlacionId
        ) {
            RegistroAuditoria auditoria = new RegistroAuditoria();
            auditoria.setId(UUID.randomUUID());
            auditoria.setActorTipo(ActorTipo.USUARIO);
            auditoria.setActorId(actorId);
            auditoria.setRol(rol);
            auditoria.setJurisdiccionSolicitada(institucionSolicitadaId != null ? "institucion:" + institucionSolicitadaId : null);
            auditoria.setOperacion("CONSULTA_EXISTENCIAS_INSTITUCION");
            auditoria.setRecursoTipo("inventario");
            auditoria.setRecursoId(institucionSolicitadaId);
            auditoria.setResultado(ResultadoAuditoria.DENEGADO); // 'denegado'
            auditoria.setCorrelacionId(correlacionId);
            auditoria.setOcurridoEn(Instant.now());

            // Detalles neutros sin datos personales ni referencias a existencias internas (EC-01, EC-02)
            auditoria.setDetalles(Map.of(
                    "motivo_rechazo", "Consulta de existencias para una institución fuera de la jurisdicción autorizada en el token JWT",
                    "solicitud_indistinguible", true
            ));

            registroAuditoriaRepository.save(auditoria);
            log.warn("AUDITORÍA (ST2): Intento de acceso denegado a inventario de institución ajena registrado. Actor: {}, Solicitada: {}, Correlación: {}",
                    actorId, institucionSolicitadaId, correlacionId);
        }

        /**
         * Helper para consulta de la proyección.
         */
        @Transactional(readOnly = true)
        public List<ExistenciasComponenteTipoSanguineoDto> consultarExistenciasPorInventarioYFiltros(
                UUID institucionId,
                LocalDate fechaActual,
                UUID componenteId,
                UUID grupoSanguineoId
        ) {
            return existenciasRepository.consultarExistenciasPorInstitucionYFiltros(institucionId, fechaActual, componenteId, grupoSanguineoId);
        }
    }

    // =========================================================================
    // 4. CONTROLADOR REST CON RESPUESTA INDISTINGUIBLE (EC-01)
    // =========================================================================

    @RestController
    @RequestMapping("/v1/inventario/existencias-institucion")
    public static class ExistenciasIndistinguibleController {

        private static final Logger log = LoggerFactory.getLogger(ExistenciasIndistinguibleController.class);
        private final ExistenciasIndistinguibleService existenciasService;

        public ExistenciasIndistinguibleController(ExistenciasIndistinguibleService existenciasService) {
            this.existenciasService = existenciasService;
        }

        /**
         * GET /v1/inventario/existencias-institucion
         *
         * Soporta la consulta opcional con 'institucion_id'. Si el parámetro especifica una institución
         * ajena a la del token JWT:
         * 1. Se registra de forma inmutable el intento DENEGADO en la bitácora de auditoría (ST2).
         * 2. Se retorna una RESPUESTA INDISTINGUIBLE (Lista vacía '[]' o 200 OK idéntico al de una
         *    institución sin existencias disponibles), impidiendo deducir si la institución existe
         *    o tiene unidades (EC-01, EC-06, RNI-03).
         */
        @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
        @PreAuthorize("hasAnyRole('operador', 'admin_banco')")
        public ResponseEntity<List<ExistenciasComponenteTipoSanguineoDto>> consultarExistenciasConRespuestaIndistinguible(
                @RequestParam(value = "institucion_id", required = false) UUID institucionIdParam,
                @RequestParam(value = "componente_id", required = false) UUID componenteId,
                @RequestParam(value = "grupo_sanguineo_id", required = false) UUID grupoSanguineoId,
                @RequestHeader(value = "X-Correlacion-Id", required = false) String correlacionHeader,
                Authentication authentication
        ) {
            String correlacionId = (correlacionHeader != null && !correlacionHeader.isBlank())
                    ? correlacionHeader : "CORR-" + UUID.randomUUID();

            Jwt jwt = (Jwt) authentication.getPrincipal();
            String actorId = jwt.getSubject();
            String rol = jwt.getClaimAsString("role");
            UUID institucionIdToken = extraerInstitucionIdDeJurisdiccion(jwt);

            // EVALUACIÓN DE JURISDICCIÓN (EC-01 / ADR-011)
            if (institucionIdParam != null && !institucionIdParam.equals(institucionIdToken)) {
                // A. Registrar el intento DENEGADO en la bitácora de auditoría (ST2 / EC-05)
                existenciasService.registrarIntentoAccesoDenegadoJurisdiccion(
                        actorId,
                        rol,
                        institucionIdParam,
                        correlacionId
                );

                log.info("Acceso a institución ajena detectado. Generando respuesta indistinguible (lista vacía) para actor: {}, institucion_solicitada: {}",
                        actorId, institucionIdParam);

                // B. RESPUESTA INDISTINGUIBLE (EC-01, RNI-03, DD Sec. 12.1):
                // Retorna 200 OK con lista vacía [], exactamente igual que si la institución no tuviera existencias
                // o no existiera en el sistema. JAMÁS retorna 403 con detalles ni mensajes reveladores.
                return ResponseEntity.ok(List.of());
            }

            // Institución autorizada -> Ejecutar consulta normal
            UUID institucionEfectiva = (institucionIdParam != null) ? institucionIdParam : institucionIdToken;
            List<ExistenciasComponenteTipoSanguineoDto> resultado = existenciasService.consultarExistenciasPorInventarioYFiltros(
                    institucionEfectiva,
                    LocalDate.now(),
                    componenteId,
                    grupoSanguineoId
            );

            return ResponseEntity.ok(resultado);
        }

        private UUID extraerInstitucionIdDeJurisdiccion(Jwt jwt) {
            String jurisdiction = jwt.getClaimAsString("jurisdiction");
            if (jurisdiction != null && jurisdiction.startsWith("institucion:")) {
                return UUID.fromString(jurisdiction.replace("institucion:", ""));
            }
            throw new IllegalStateException("El token JWT no contiene una reivindicación de jurisdicción de institución válida");
        }
    }
}
