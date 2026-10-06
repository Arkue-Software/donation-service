package co.gov.redvital.donacion.domain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * =============================================================================
 * REDVITAL - Plataforma Nacional de Gestión de Bancos de Sangre
 * Servicio de Donación (redvital-donacion-service - Java 25 / Spring Boot 4.1)
 * =============================================================================
 *
 * ESPECIFICACIÓN DE CONSULTA ÚNICA DE UNIDADES DISPONIBLES (DS-04)
 *
 * Cumple de forma estricta con las reglas de integridad y escenarios de calidad:
 * - DS-04 / RF-07 / EC-12: Consulta de disponibilidad basada en filtrado inclusivo estricto.
 * - IN-22: El inventario, el escalamiento y la reserva para despacho solo consideran unidades
 *   cuyo estado tiene 'cuenta_disponible = TRUE' en el catálogo estado_unidad.
 * - RNI-04 / EC-12 / RNF-08: Bloqueo estricto ante estado indeterminado. Ante la duda o vacíos de
 *   información (apta = NULL, apta = FALSE, fecha_vencimiento expirada, o estado sin cuenta_disponible),
 *   la unidad queda EXCLUIDA del inventario por construcción.
 * - EC-02 / RI-02 / Anexo A: Ausencia total de causas clínicas, diagnósticos o resultados individuales.
 * - RNF-04 / EC-15: Garantiza la velocidad de respuesta de la proyección sobre unidad.
 */
public class ConsultaInventarioDisponibleDS04 {

    // =========================================================================
    // 1. MODELO DE PROYECCIONES Y DTOs (CONTRATO REST)
    // =========================================================================

    /**
     * DTO de Proyección Resumida de Existencias por Componente y Grupo Sanguíneo (RF-07).
     */
    public record ResumenExistenciasDto(
            UUID institucionCustodiaId,
            UUID componenteId,
            UUID grupoSanguineoId,
            long cantidadDisponible,
            LocalDate fechaVencimientoMasProxima
    ) {}

    /**
     * DTO de Detalle de Unidad Disponible (sin datos clínicos ni de donante).
     */
    public record UnidadDisponibleDetalleDto(
            UUID unidadId,
            UUID donacionId,
            UUID componenteId,
            UUID grupoSanguineoId,
            UUID institucionCustodiaId,
            Boolean apta,
            LocalDate fechaVencimiento,
            Integer volumenMl,
            String estadoCodigo
    ) {}

    // =========================================================================
    // 2. REPOSITORIO JPA CON LA CONSULTA ÚNICA DS-04 (IN-22, EC-12, RNI-04)
    // =========================================================================

    @Repository
    public interface UnidadInventarioRepository extends JpaRepository<UnidadEntity, UUID> {

        /**
         * Consulta Única de Inventario Agregado (DS-04 / RF-07 / IN-22 / EC-12).
         *
         * Aplica la proyección estricta sobre la entidad unidad uniendo el catálogo estado_unidad:
         * 1. e.cuentaDisponible = TRUE (Único estado 'disponible')
         * 2. u.apta = TRUE (Tamizaje verificado)
         * 3. u.fechaVencimiento >= :fechaActual (Sin vencer)
         * 4. u.institucionCustodiaId IN (:instituciones) (Filtrado por Jurisdicción)
         *
         * ANTE ESTADO INDETERMINADO (apta IS NULL, apta = FALSE, fecha_vencimiento < fechaActual,
         * o cuenta_disponible = FALSE), LA FILA ES EXCLUIDA AUTOMÁTICAMENTE POR LA CLÁUSULA
         * WHERE INCLUSIVA (RNI-04).
         */
        @Query("""
            SELECT new co.gov.redvital.donacion.domain.service.ConsultaInventarioDisponibleDS04$ResumenExistenciasDto(
                u.institucionCustodiaId,
                u.componenteId,
                u.grupoSanguineoId,
                COUNT(u.id),
                MIN(u.fechaVencimiento)
            )
            FROM UnidadEntity u
            JOIN EstadoUnidadEntity e ON u.estadoId = e.id
            WHERE u.institucionCustodiaId IN :instituciones
              AND e.cuentaDisponible = true
              AND u.apta = true
              AND u.fechaVencimiento >= :fechaActual
              AND (:componenteId IS NULL OR u.componenteId = :componenteId)
              AND (:grupoSanguineoId IS NULL OR u.grupoSanguineoId = :grupoSanguineoId)
            GROUP BY u.institucionCustodiaId, u.componenteId, u.grupoSanguineoId
            """)
        List<ResumenExistenciasDto> consultarResumenInventarioDisponibleDS04(
                @Param("instituciones") List<UUID> instituciones,
                @Param("fechaActual") LocalDate fechaActual,
                @Param("componenteId") UUID componenteId,
                @Param("grupoSanguineoId") UUID grupoSanguineoId
        );

        /**
         * Consulta Única de Detalle de Unidades Disponibles (DS-04).
         */
        @Query("""
            SELECT u
            FROM UnidadEntity u
            JOIN EstadoUnidadEntity e ON u.estadoId = e.id
            WHERE u.institucionCustodiaId IN :instituciones
              AND e.cuentaDisponible = true
              AND u.apta = true
              AND u.fechaVencimiento >= :fechaActual
              AND (:componenteId IS NULL OR u.componenteId = :componenteId)
              AND (:grupoSanguineoId IS NULL OR u.grupoSanguineoId = :grupoSanguineoId)
            ORDER BY u.fechaVencimiento ASC
            """)
        List<UnidadEntity> findUnidadesDisponiblesDS04(
                @Param("instituciones") List<UUID> instituciones,
                @Param("fechaActual") LocalDate fechaActual,
                @Param("componenteId") UUID componenteId,
                @Param("grupoSanguineoId") UUID grupoSanguineoId
        );
    }

    // =========================================================================
    // 3. SERVICIO DE DOMINIO TRANSACCIONAL
    // =========================================================================

    @Service
    public static class InventarioDisponibleService {

        private static final Logger log = LoggerFactory.getLogger(InventarioDisponibleService.class);
        private final UnidadInventarioRepository unidadRepository;

        public InventarioDisponibleService(UnidadInventarioRepository unidadRepository) {
            this.unidadRepository = unidadRepository;
        }

        @Transactional(readOnly = true)
        public List<ResumenExistenciasDto> consultarExistenciasAgregadas(
                List<UUID> institucionesIds,
                UUID componenteId,
                UUID grupoSanguineoId
        ) {
            if (institucionesIds == null || institucionesIds.isEmpty()) {
                log.warn("Consulta de existencias invocada sin instituciones autorizadas.");
                return List.of();
            }

            LocalDate fechaActual = LocalDate.now();
            return unidadRepository.consultarResumenInventarioDisponibleDS04(
                    institucionesIds,
                    fechaActual,
                    componenteId,
                    grupoSanguineoId
            );
        }

        @Transactional(readOnly = true)
        public List<UnidadDisponibleDetalleDto> consultarUnidadesDisponiblesDetalle(
                List<UUID> institucionesIds,
                UUID componenteId,
                UUID grupoSanguineoId
        ) {
            if (institucionesIds == null || institucionesIds.isEmpty()) {
                return List.of();
            }

            LocalDate fechaActual = LocalDate.now();
            List<UnidadEntity> unidades = unidadRepository.findUnidadesDisponiblesDS04(
                    institucionesIds,
                    fechaActual,
                    componenteId,
                    grupoSanguineoId
            );

            return unidades.stream()
                    .map(u -> new UnidadDisponibleDetalleDto(
                            u.getId(),
                            u.getDonacionId(),
                            u.getComponenteId(),
                            u.getGrupoSanguineoId(),
                            u.getInstitucionCustodiaId(),
                            u.getApta(),
                            u.getFechaVencimiento(),
                            u.getVolumenMl(),
                            "disponible"
                    ))
                    .toList();
        }
    }

    // =========================================================================
    // 4. CONTROLADOR REST (POST / GET ENPOINTS)
    // =========================================================================

    @RestController
    @RequestMapping("/v1/inventario")
    public static class InventarioController {

        private static final Logger log = LoggerFactory.getLogger(InventarioController.class);
        private final InventarioDisponibleService inventarioDisponibleService;

        public InventarioController(InventarioDisponibleService inventarioDisponibleService) {
            this.inventarioDisponibleService = inventarioDisponibleService;
        }

        /**
         * Endpoint de Consulta Única de Existencias Agregadas (GET /v1/inventario - RF-07)
         */
        @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
        @PreAuthorize("hasAnyRole('operador', 'admin_banco', 'coordinador', 'admin_nacional')")
        public ResponseEntity<List<ResumenExistenciasDto>> consultarInventarioDisponibleDS04(
                @RequestParam(value = "componente_id", required = false) UUID componenteId,
                @RequestParam(value = "grupo_sanguineo_id", required = false) UUID grupoSanguineoId,
                @RequestHeader(value = "X-Correlacion-Id", required = false) String correlacionId,
                Authentication authentication
        ) {
            Jwt jwt = (Jwt) authentication.getPrincipal();
            List<UUID> institucionesJurisdiccion = resolverInstitucionesDeJurisdiccion(jwt);

            log.info("Ejecutando Consulta Única DS-04 de Inventario. Instituciones en Jurisdicción: {}, Correlación: {}",
                    institucionesJurisdiccion.size(), correlacionId);

            List<ResumenExistenciasDto> resultado = inventarioDisponibleService.consultarExistenciasAgregadas(
                    institucionesJurisdiccion,
                    componenteId,
                    grupoSanguineoId
            );

            return ResponseEntity.ok(resultado);
        }

        /**
         * Endpoint de Consulta de Detalle de Unidades Disponibles (GET /v1/inventario/unidades)
         */
        @GetMapping(value = "/unidades", produces = MediaType.APPLICATION_JSON_VALUE)
        @PreAuthorize("hasAnyRole('operador', 'admin_banco', 'coordinador', 'admin_nacional')")
        public ResponseEntity<List<UnidadDisponibleDetalleDto>> consultarUnidadesDisponiblesDetalle(
                @RequestParam(value = "componente_id", required = false) UUID componenteId,
                @RequestParam(value = "grupo_sanguineo_id", required = false) UUID grupoSanguineoId,
                Authentication authentication
        ) {
            Jwt jwt = (Jwt) authentication.getPrincipal();
            List<UUID> institucionesJurisdiccion = resolverInstitucionesDeJurisdiccion(jwt);

            List<UnidadDisponibleDetalleDto> unidades = inventarioDisponibleService.consultarUnidadesDisponiblesDetalle(
                    institucionesJurisdiccion,
                    componenteId,
                    grupoSanguineoId
            );

            return ResponseEntity.ok(unidades);
        }

        private List<UUID> resolverInstitucionesDeJurisdiccion(Jwt jwt) {
            String jurisdiction = jwt.getClaimAsString("jurisdiction");
            if (jurisdiction == null || jurisdiction.isBlank()) {
                return List.of();
            }

            if (jurisdiction.startsWith("institucion:")) {
                return List.of(UUID.fromString(jurisdiction.replace("institucion:", "")));
            }

            List<String> instClaims = jwt.getClaimAsStringList("instituciones_jurisdiccion");
            if (instClaims != null && !instClaims.isEmpty()) {
                return instClaims.stream().map(UUID::fromString).toList();
            }

            return List.of();
        }
    }

    // =========================================================================
    // 5. ENTIDADES MOCK PARA DEFINICIÓN DE ESTRUCTURA JPA
    // =========================================================================

    @Entity
    @Table(name = "unidad")
    public static class UnidadEntity {
        @Id private UUID id;
        @Column(name = "donacion_id") private UUID donacionId;
        @Column(name = "componente_id") private UUID componenteId;
        @Column(name = "grupo_sanguineo_id") private UUID grupoSanguineoId;
        @Column(name = "institucion_custodia_id") private UUID institucionCustodiaId;
        @Column(name = "estado_id") private UUID estadoId;
        private Boolean apta;
        @Column(name = "fecha_vencimiento") private LocalDate fechaVencimiento;
        @Column(name = "volumen_ml") private Integer volumenMl;

        public UUID getId() { return id; }
        public UUID getDonacionId() { return donacionId; }
        public UUID getComponenteId() { return componenteId; }
        public UUID getGrupoSanguineoId() { return grupoSanguineoId; }
        public UUID getInstitucionCustodiaId() { return institucionCustodiaId; }
        public UUID getEstadoId() { return estadoId; }
        public Boolean getApta() { return apta; }
        public LocalDate getFechaVencimiento() { return fechaVencimiento; }
        public Integer getVolumenMl() { return volumenMl; }
    }

    @Entity
    @Table(name = "estado_unidad")
    public static class EstadoUnidadEntity {
        @Id private UUID id;
        private String codigo;
        @Column(name = "cuenta_disponible") private Boolean cuentaDisponible;

        public UUID getId() { return id; }
        public String getCodigo() { return codigo; }
        public Boolean getCuentaDisponible() { return cuentaDisponible; }
    }
}
