package co.gov.redvital.donacion.infrastructure.scheduler;

import co.gov.redvital.donacion.domain.model.ActorTipo;
import co.gov.redvital.donacion.domain.service.MotorTransicionEstadoUnidad;
import co.gov.redvital.donacion.domain.service.MotorTransicionEstadoUnidad.TransicionRequest;
import co.gov.redvital.donacion.infrastructure.repository.UnidadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * =============================================================================
 * REDVITAL - Plataforma Nacional de Gestión de Bancos de Sangre
 * Servicio de Donación (redvital-donacion-service - Java 25 / Spring Boot 4.1)
 * =============================================================================
 *
 * SUITE DE PRUEBAS DE VENCIMIENTO DE UNIDADES SEMBRADAS (DS-06 / RF-18 / EC-13)
 *
 * Evalúa el job de vencimiento automático (VencimientoUnidadesScheduledJobDS06)
 * sobre las unidades definidas en el conjunto sintético del Sprint 3 (T-302.1):
 *
 * - UNI-VENCIDA-A1: Unidad previamente en estado 'vencida'. No debe re-vencerse.
 * - UNI-VENCE-10M y UNI-VENCE-14M: Unidades que alcanzan la expiración en la ventana de prueba.
 * - UNI-VENCE-MEDIANOCHE: Unidades cerca del cambio de día UTC (23:50 y 00:10 hora local UTC-5)
 *   evaluadas bajo la regla de forzado de zona horaria UTC (ZoneOffset.UTC).
 * - Bloqueo Consultivo PostgreSQL (DS-06): pg_try_advisory_xact_lock(LOCK_ID).
 * - Atribución al Actor SISTEMA (EC-33): actor_id = 'SISTEMA_VENCIMIENTO_AUTOMATICO'.
 * - Correlación por Ciclo (EC-20): Trazabilidad unificada por iteración.
 * - Aislamiento de Errores en Lote: Continuidad de procesamiento si falla una unidad.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Pruebas de Vencimiento Automático con Unidades Sembradas Sintéticas (DS-06 / EC-13)")
class VencimientoUnidadesSembradasTest {

    @Mock
    private MotorTransicionEstadoUnidad motorTransicionService;

    @Mock
    private UnidadRepository unidadRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Captor
    private ArgumentCaptor<TransicionRequest> transicionCaptor;

    private VencimientoUnidadesScheduledJobDS06 schedulerJob;

    // UUIDs representativos de las unidades sembradas en el conjunto sintético (T-302.1 / Sección 3.2)
    private static final UUID UNI_VENCE_10M_ID = UUID.fromString("a1111111-0000-0000-0000-000000000001");
    private static final UUID UNI_VENCE_14M_ID = UUID.fromString("a1111111-0000-0000-0000-000000000002");
    private static final UUID UNI_VENCE_MEDIANOCHE_UTC_ID = UUID.fromString("a1111111-0000-0000-0000-000000000003");
    private static final UUID UNI_DISP_A1_VIGENTE_ID = UUID.fromString("b2222222-0000-0000-0000-000000000001");
    private static final UUID UNI_VENCIDA_A1_ID = UUID.fromString("c3333333-0000-0000-0000-000000000001");

    private static final long LOCK_ID_DS06 = 0x56454E434155544FL;

    @BeforeEach
    void setUp() {
        schedulerJob = new VencimientoUnidadesScheduledJobDS06(
                motorTransicionService,
                unidadRepository,
                jdbcTemplate
        );
        schedulerJob.inicializarZonaHorariaUTC();
    }

    @Nested
    @DisplayName("1. Adquisición de Bloqueo Consultivo PostgreSQL (DS-06)")
    class BloqueoConsultivoTest {

        @Test
        @DisplayName("DS-06: Si otra instancia posee el candado consultivo, el job omite la ejecución")
        void testBloqueoConsultivoOcupado() {
            // Simula que pg_try_advisory_xact_lock retorna false (ocupado por otro pod)
            when(jdbcTemplate.queryForObject(
                    eq("SELECT pg_try_advisory_xact_lock(?)"),
                    eq(Boolean.class),
                    eq(LOCK_ID_DS06)
            )).thenReturn(Boolean.FALSE);

            schedulerJob.ejecutarCicloVencimientoAutomatico();

            // Verificación: No debe consultar unidades expiradas ni llamar al motor
            verify(unidadRepository, never()).findIdsUnidadesExpiradasParaVencimiento(any());
            verify(motorTransicionService, never()).procesarTransicion(any());
        }

        @Test
        @DisplayName("DS-06: Si obtiene el candado consultivo, procesa el lote de unidades expiradas")
        void testBloqueoConsultivoAdquirido() {
            when(jdbcTemplate.queryForObject(
                    eq("SELECT pg_try_advisory_xact_lock(?)"),
                    eq(Boolean.class),
                    eq(LOCK_ID_DS06)
            )).thenReturn(Boolean.TRUE);

            when(unidadRepository.findIdsUnidadesExpiradasParaVencimiento(any(LocalDate.class)))
                    .thenReturn(List.of());

            schedulerJob.ejecutarCicloVencimientoAutomatico();

            verify(unidadRepository, times(1)).findIdsUnidadesExpiradasParaVencimiento(any(LocalDate.class));
        }
    }

    @Nested
    @DisplayName("2. Procesamiento de Unidades Sembradas Expiradas (conjunto-sintetico-sprint-3)")
    class UnidadesSembradasExpiracionTest {

        @Test
        @DisplayName("EC-13 / RF-18: Transita a 'vencida' las unidades sembradas expiradas (UNI-VENCE-10M y UNI-VENCE-14M)")
        void testProcesarUnidadesSembradasExpiradas() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), anyLong()))
                    .thenReturn(Boolean.TRUE);

            // Simula que la consulta del repositorio devuelve las 2 unidades sintéticas expiradas
            when(unidadRepository.findIdsUnidadesExpiradasParaVencimiento(any(LocalDate.class)))
                    .thenReturn(List.of(UNI_VENCE_10M_ID, UNI_VENCE_14M_ID));

            schedulerJob.ejecutarCicloVencimientoAutomatico();

            // Debe haber ejecutado 2 transiciones en el motor único
            verify(motorTransicionService, times(2)).procesarTransicion(transicionCaptor.capture());

            List<TransicionRequest> requests = transicionCaptor.getAllValues();
            assertEquals(2, requests.size());

            // Validación de parámetros normativos para UNI-VENCE-10M
            TransicionRequest req1 = requests.get(0);
            assertEquals(UNI_VENCE_10M_ID, req1.unidadId());
            assertEquals("vencida", req1.estadoDestinoCodigo());
            assertEquals(ActorTipo.SISTEMA, req1.actorTipo(), "Debe ser actor SISTEMA (EC-33)");
            assertEquals("SISTEMA_VENCIMIENTO_AUTOMATICO", req1.actorId());
            assertEquals("sistema", req1.rol());

            // Validación de parámetros normativos para UNI-VENCE-14M
            TransicionRequest req2 = requests.get(1);
            assertEquals(UNI_VENCE_14M_ID, req2.unidadId());
            assertEquals("vencida", req2.estadoDestinoCodigo());
            assertEquals(ActorTipo.SISTEMA, req2.actorTipo());
            assertEquals("SISTEMA_VENCIMIENTO_AUTOMATICO", req2.actorId());
        }

        @Test
        @DisplayName("Exclusión de Unidades Vigentes (UNI-DISP-A1) y Unidades Ya Vencidas (UNI-VENCIDA-A1)")
        void testExclusionDeUnidadesNoExpiradasOYAVencidas() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), anyLong()))
                    .thenReturn(Boolean.TRUE);

            // El repositorio filtra por estado origen (disponible, reservada, fraccionada, en_tamizaje)
            // y por fecha_vencimiento < hoy UTC. Las unidades vigentes o ya 'vencidas' no retornan.
            when(unidadRepository.findIdsUnidadesExpiradasParaVencimiento(any(LocalDate.class)))
                    .thenReturn(List.of()); // Repositorio devuelve lista vacía

            schedulerJob.ejecutarCicloVencimientoAutomatico();

            // El motor nunca es invocado para unidades vigentes o ya terminales
            verify(motorTransicionService, never()).procesarTransicion(any());
        }

        @Test
        @DisplayName("Evaluación en Borde de Medianoche UTC (UNI-VENCE-MEDIANOCHE)")
        void testUnidadesBordeMedianocheUTC() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), anyLong()))
                    .thenReturn(Boolean.TRUE);

            LocalDate fechaHoyUTC = LocalDate.now(ZoneOffset.UTC);

            when(unidadRepository.findIdsUnidadesExpiradasParaVencimiento(eq(fechaHoyUTC)))
                    .thenReturn(List.of(UNI_VENCE_MEDIANOCHE_UTC_ID));

            schedulerJob.ejecutarCicloVencimientoAutomatico();

            verify(unidadRepository).findIdsUnidadesExpiradasParaVencimiento(eq(fechaHoyUTC));
            verify(motorTransicionService, times(1)).procesarTransicion(transicionCaptor.capture());

            TransicionRequest req = transicionCaptor.getValue();
            assertEquals(UNI_VENCE_MEDIANOCHE_UTC_ID, req.unidadId());
            assertEquals("vencida", req.estadoDestinoCodigo());
        }
    }

    @Nested
    @DisplayName("3. Resiliencia y Aislamiento de Errores en Lote")
    class ResilienciaErroresLoteTest {

        @Test
        @DisplayName("Aislamiento de fallos: Si una unidad falla en el motor, las demás del lote continúan procesándose")
        void testContinuidadDeLoteAnteFalloIndividial() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), anyLong()))
                    .thenReturn(Boolean.TRUE);

            when(unidadRepository.findIdsUnidadesExpiradasParaVencimiento(any(LocalDate.class)))
                    .thenReturn(List.of(UNI_VENCE_10M_ID, UNI_VENCE_14M_ID));

            // Simula que la primera unidad falla en el motor (ej. excepción de bloqueo)
            doThrow(new RuntimeException("Error simulado de base de datos"))
                    .when(motorTransicionService)
                    .procesarTransicion(argThat(req -> req.unidadId().equals(UNI_VENCE_10M_ID)));

            // La segunda unidad se procesa normalmente
            doReturn(null)
                    .when(motorTransicionService)
                    .procesarTransicion(argThat(req -> req.unidadId().equals(UNI_VENCE_14M_ID)));

            assertDoesNotThrow(() -> schedulerJob.ejecutarCicloVencimientoAutomatico());

            // Verifica que se intentaron ambas unidades
            verify(motorTransicionService, times(2)).procesarTransicion(any());
        }
    }
}
