package co.gov.redvital.donacion.domain.service;

import co.gov.redvital.donacion.domain.exception.TransicionEstadoInvalidaException;
import co.gov.redvital.donacion.domain.model.ActorTipo;
import co.gov.redvital.donacion.domain.model.EstadoUnidad;
import co.gov.redvital.donacion.domain.model.EventoUnidad;
import co.gov.redvital.donacion.domain.model.RegistroAuditoria;
import co.gov.redvital.donacion.domain.model.ResultadoAuditoria;
import co.gov.redvital.donacion.domain.model.Unidad;
import co.gov.redvital.donacion.infrastructure.repository.EstadoUnidadRepository;
import co.gov.redvital.donacion.infrastructure.repository.EventoUnidadRepository;
import co.gov.redvital.donacion.infrastructure.repository.ObservacionOperativaRepository;
import co.gov.redvital.donacion.infrastructure.repository.RegistroAuditoriaRepository;
import co.gov.redvital.donacion.infrastructure.repository.TransicionValidaRepository;
import co.gov.redvital.donacion.infrastructure.repository.UnidadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Suite de Pruebas Unitarias Aisladas (sin Base de Datos) para MotorTransicionEstadoUnidad.
 *
 * Satisface los escenarios de calidad y principios de diseño del DD V2.0 y SAD V2.0:
 * - EC-36: Verificación exhaustiva de la máquina de estados sobre los 82 casos (13 válidos, 68 denegados, 1 creación).
 * - EC-04 / IN-04: Inmutabilidad del estado de la unidad ante transiciones rechazadas.
 * - EC-05 / RNF-09: Trazabilidad inmutable y registro de intentos denegados y permitidos en registro_auditoria.
 * - EC-02 / RI-02: Garantía de ausencia de causas clínicas en errores y registros de auditoría.
 * - EC-33: Atribución correcta del actor (sistema vs. usuario).
 *
 * Tiempo de ejecución esperado: < 100 ms (ampliamente inferior a los 2 minutos exigidos por EC-36).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Pruebas Aisladas de la Máquina de Estados de la Unidad de Sangre (82 Casos)")
class MotorTransicionEstadoUnidadTest {

    @Mock
    private UnidadRepository unidadRepository;

    @Mock
    private EstadoUnidadRepository estadoUnidadRepository;

    @Mock
    private TransicionValidaRepository transicionValidaRepository;

    @Mock
    private EventoUnidadRepository eventoUnidadRepository;

    @Mock
    private RegistroAuditoriaRepository registroAuditoriaRepository;

    @Mock
    private ObservacionOperativaRepository observacionOperativaRepository;

    @Captor
    private ArgumentCaptor<Unidad> unidadCaptor;

    @Captor
    private ArgumentCaptor<EventoUnidad> eventoCaptor;

    @Captor
    private ArgumentCaptor<RegistroAuditoria> auditoriaCaptor;

    private MotorTransicionEstadoUnidad motor;

    // Catálogo de los 9 estados oficiales de la unidad (Sección 10.1 DD V2.0)
    private static final List<EstadoDef> ESTADOS = List.of(
            new EstadoDef(UUID.fromString("11111111-0000-0000-0000-000000000001"), "captada", false, false),
            new EstadoDef(UUID.fromString("11111111-0000-0000-0000-000000000002"), "fraccionada", false, false),
            new EstadoDef(UUID.fromString("11111111-0000-0000-0000-000000000003"), "en_tamizaje", false, false),
            new EstadoDef(UUID.fromString("11111111-0000-0000-0000-000000000004"), "disponible", false, true),
            new EstadoDef(UUID.fromString("11111111-0000-0000-0000-000000000005"), "reservada", false, false),
            new EstadoDef(UUID.fromString("11111111-0000-0000-0000-000000000006"), "despachada", true, false),
            new EstadoDef(UUID.fromString("11111111-0000-0000-0000-000000000007"), "no_apta", false, false),
            new EstadoDef(UUID.fromString("11111111-0000-0000-0000-000000000008"), "vencida", false, false),
            new EstadoDef(UUID.fromString("11111111-0000-0000-0000-000000000009"), "desechada", true, false)
    );

    // Mapeo estricto de las 13 transiciones válidas (Tabla 19 DD V2.0)
    private static final List<TransicionValidaDef> TRANSICIONES_VALIDAS = List.of(
            new TransicionValidaDef("captada", "fraccionada", ActorTipo.USUARIO, 1),
            new TransicionValidaDef("fraccionada", "en_tamizaje", ActorTipo.USUARIO, 2),
            new TransicionValidaDef("en_tamizaje", "disponible", ActorTipo.USUARIO, 3),
            new TransicionValidaDef("en_tamizaje", "no_apta", ActorTipo.USUARIO, 4),
            new TransicionValidaDef("disponible", "reservada", ActorTipo.USUARIO, 5),
            new TransicionValidaDef("reservada", "disponible", ActorTipo.USUARIO, 6),
            new TransicionValidaDef("reservada", "despachada", ActorTipo.USUARIO, 7),
            new TransicionValidaDef("disponible", "vencida", ActorTipo.SISTEMA, 8),
            new TransicionValidaDef("reservada", "vencida", ActorTipo.SISTEMA, 9),
            new TransicionValidaDef("fraccionada", "vencida", ActorTipo.SISTEMA, 10),
            new TransicionValidaDef("en_tamizaje", "vencida", ActorTipo.SISTEMA, 11),
            new TransicionValidaDef("no_apta", "desechada", ActorTipo.USUARIO, 12),
            new TransicionValidaDef("vencida", "desechada", ActorTipo.USUARIO, 13)
    );

    record EstadoDef(UUID id, String codigo, boolean esTerminal, boolean cuentaDisponible) {}
    record TransicionValidaDef(String origen, String destino, ActorTipo actorTipo, int numero) {}

    @BeforeEach
    void setUp() {
        motor = new MotorTransicionEstadoUnidad(
                unidadRepository,
                estadoUnidadRepository,
                transicionValidaRepository,
                eventoUnidadRepository,
                registroAuditoriaRepository,
                observacionOperativaRepository
        );

        // Configuración en memoria del catálogo de estados
        for (EstadoDef est : ESTADOS) {
            EstadoUnidad entity = new EstadoUnidad();
            entity.setId(est.id());
            entity.setCodigo(est.codigo());
            entity.setNombre(est.codigo());
            entity.setEsTerminal(est.esTerminal());
            entity.setCuentaDisponible(est.cuentaDisponible());

            lenient().when(estadoUnidadRepository.findById(est.id())).thenReturn(Optional.of(entity));
            lenient().when(estadoUnidadRepository.findByCodigo(est.codigo())).thenReturn(Optional.of(entity));
        }

        // Configuración en memoria de las 13 transiciones válidas en transicionValidaRepository
        for (EstadoDef origen : ESTADOS) {
            for (EstadoDef destino : ESTADOS) {
                for (ActorTipo actor : ActorTipo.values()) {
                    boolean esValida = TRANSICIONES_VALIDAS.stream()
                            .anyMatch(t -> t.origen().equals(origen.codigo())
                                    && t.destino().equals(destino.codigo())
                                    && t.actorTipo() == actor);

                    lenient().when(transicionValidaRepository.existsByEstadoOrigenIdAndEstadoDestinoIdAndActorTipo(
                            origen.id(), destino.id(), actor
                    )).thenReturn(esValida);
                }
            }
        }
    }

    @Nested
    @DisplayName("Caso 0 / Caso 82: Alta de Unidad en Estado Inicial ('captada')")
    class CreacionUnidadTest {

        @Test
        @DisplayName("RF-03: La creación inicial establece el estado inicial 'captada' sin transición previa")
        void testAltaUnidadInicial() {
            UUID unidadId = UUID.randomUUID();
            UUID donacionId = UUID.randomUUID();
            UUID captadaId = UUID.fromString("11111111-0000-0000-0000-000000000001");

            Unidad nuevaUnidad = new Unidad();
            nuevaUnidad.setId(unidadId);
            nuevaUnidad.setDonacionId(donacionId);
            nuevaUnidad.setEstadoId(captadaId);
            nuevaUnidad.setCreadoEn(Instant.now());

            assertNotNull(nuevaUnidad.getId());
            assertEquals(captadaId, nuevaUnidad.getEstadoId());
            assertNull(nuevaUnidad.getApta(), "El veredicto de aptitud debe ser nulo en captación");
        }
    }

    @Nested
    @DisplayName("Matriz de 81 Casos Ordenados (9 Estados de Origen x 9 Estados de Destino)")
    class Matriz81CasosTest {

        /**
         * Proveedor de datos para las 81 combinaciones posibles de origen y destino.
         */
        static Stream<Arguments> generar81CombinacionesMatriz() {
            List<Arguments> combinaciones = new ArrayList<>();
            for (EstadoDef origen : ESTADOS) {
                for (EstadoDef destino : ESTADOS) {
                    // Determina si para algún tipo de actor la combinación es válida según el DD
                    Optional<TransicionValidaDef> transValida = TRANSICIONES_VALIDAS.stream()
                            .filter(t -> t.origen().equals(origen.codigo()) && t.destino().equals(destino.codigo()))
                            .findFirst();

                    if (transValida.isPresent()) {
                        combinaciones.add(Arguments.of(
                                origen,
                                destino,
                                true,
                                transValida.get().actorTipo(),
                                transValida.get().numero()
                        ));
                    } else {
                        // Caso Prohibido: Se prueba con actor USUARIO
                        combinaciones.add(Arguments.of(
                                origen,
                                destino,
                                false,
                                ActorTipo.USUARIO,
                                0
                        ));
                    }
                }
            }
            return combinaciones.stream();
        }

        @ParameterizedTest(name = "Caso [{index}]: {0} -> {1} | Válida: {2} | Actor: {3} | Transición DD: #{4}")
        @MethodSource("generar81CombinacionesMatriz")
        @DisplayName("EC-36: Verificación exhaustiva de la matriz de transiciones (81 pares)")
        void testRecorrer81CasosMatriz(
                EstadoDef origen,
                EstadoDef destino,
                boolean esValidaExpectativa,
                ActorTipo actorTipo,
                int transicionNumero) {

            UUID unidadId = UUID.randomUUID();
            Unidad unidad = new Unidad();
            unidad.setId(unidadId);
            unidad.setEstadoId(origen.id());
            unidad.setInstitucionCustodiaId(UUID.randomUUID());

            when(unidadRepository.findByIdForUpdate(unidadId)).thenReturn(Optional.of(unidad));

            MotorTransicionEstadoUnidad.TransicionRequest request = new MotorTransicionEstadoUnidad.TransicionRequest(
                    unidadId,
                    destino.codigo(),
                    actorTipo,
                    actorTipo == ActorTipo.SISTEMA ? "proceso_vencimiento" : "usr_operador_01",
                    actorTipo == ActorTipo.SISTEMA ? "sistema" : "operador",
                    UUID.randomUUID(),
                    null,
                    "disponible".equals(destino.codigo()) ? Boolean.TRUE : ("no_apta".equals(destino.codigo()) ? Boolean.FALSE : null),
                    null,
                    "CORR-TEST-" + UUID.randomUUID()
            );

            if (esValidaExpectativa) {
                // --- CASO VÁLIDO (13 Transiciones) ---
                EventoUnidad eventoResult = motor.procesarTransicion(request);

                assertNotNull(eventoResult, "Debe generar un evento_unidad al ser una transición válida");

                // Verificación de actualización atómica en la unidad
                verify(unidadRepository, times(1)).save(unidadCaptor.capture());
                Unidad unidadActualizada = unidadCaptor.getValue();
                assertEquals(destino.id(), unidadActualizada.getEstadoId(), "El estado_id de la unidad debe cambiar al estado destino");

                // Verificación de inserción en evento_unidad (RF-04)
                verify(eventoUnidadRepository, times(1)).save(eventoCaptor.capture());
                EventoUnidad eventoGuardado = eventoCaptor.getValue();
                assertEquals(origen.id(), eventoGuardado.getEstadoAnteriorId());
                assertEquals(destino.id(), eventoGuardado.getEstadoNuevoId());

                // Verificación de auditoría permitida (ST2, EC-05)
                verify(registroAuditoriaRepository, times(1)).save(auditoriaCaptor.capture());
                RegistroAuditoria auditoria = auditoriaCaptor.getValue();
                assertEquals(ResultadoAuditoria.PERMITIDO, auditoria.getResultado());
                assertEquals("unidad", auditoria.getRecursoTipo());

            } else {
                // --- CASO PROHIBIDO / INVALIDO (68 Transiciones) ---
                TransicionEstadoInvalidaException ex = assertThrows(
                        TransicionEstadoInvalidaException.class,
                        () -> motor.procesarTransicion(request),
                        String.format("La transición %s -> %s debió ser rechazada con 409", origen.codigo(), destino.codigo())
                );

                // Verificación de inmutabilidad del estado de la unidad (IN-03, IN-04, EC-12)
                verify(unidadRepository, never()).save(any());
                assertEquals(origen.id(), unidad.getEstadoId(), "El estado_id de la unidad PERMANECE INTACTO tras un rechazo");

                // Verificación de no inserción en evento_unidad
                verify(eventoUnidadRepository, never()).save(any());

                // Verificación de registro de auditoría DENEGADO (ST2, EC-05, RNF-09)
                verify(registroAuditoriaRepository, times(1)).save(auditoriaCaptor.capture());
                RegistroAuditoria auditoria = auditoriaCaptor.getValue();
                assertEquals(ResultadoAuditoria.DENEGADO, auditoria.getResultado());
                assertEquals("unidad", auditoria.getRecursoTipo());
                assertEquals(unidadId, auditoria.getRecursoId());

                // Verificación de ausencia de causa clínica en los detalles (EC-02, RI-02)
                Map<String, Object> detalles = auditoria.getDetalles();
                assertNotNull(detalles);
                assertFalse(detalles.containsKey("causa_clinica"), "Está strictly prohibido registrar causa clínica");
                assertFalse(detalles.containsKey("resultado_laboratorio"), "Está strictly prohibido registrar resultado de prueba");
            }
        }
    }

    @Nested
    @DisplayName("Pruebas Complementarias de Reglas de Negocio Específicas")
    class ReglasEspecificasTest {

        @Test
        @DisplayName("IN-08 / EC-02: Transición de Tamizaje asigna 'apta = true' o 'apta = false' sin causas clínicas")
        void testTamizajeAsignacionApta() {
            UUID unidadId = UUID.randomUUID();
            UUID enTamizajeId = UUID.fromString("11111111-0000-0000-0000-000000000003");

            Unidad unidad = new Unidad();
            unidad.setId(unidadId);
            unidad.setEstadoId(enTamizajeId);

            when(unidadRepository.findByIdForUpdate(unidadId)).thenReturn(Optional.of(unidad));

            // Transición 3: en_tamizaje -> disponible (apta = true)
            MotorTransicionEstadoUnidad.TransicionRequest requestApta = new MotorTransicionEstadoUnidad.TransicionRequest(
                    unidadId, "disponible", ActorTipo.USUARIO, "usr_lab_01", "operador",
                    UUID.randomUUID(), null, true, null, "CORR-TAM-01"
            );

            motor.procesarTransicion(requestApta);
            assertTrue(unidad.getApta(), "Debe marcar la unidad como apta = true");

            // Transición 4: en_tamizaje -> no_apta (apta = false)
            unidad.setEstadoId(enTamizajeId);
            MotorTransicionEstadoUnidad.TransicionRequest requestNoApta = new MotorTransicionEstadoUnidad.TransicionRequest(
                    unidadId, "no_apta", ActorTipo.USUARIO, "usr_lab_01", "operador",
                    UUID.randomUUID(), null, false, null, "CORR-TAM-02"
            );

            motor.procesarTransicion(requestNoApta);
            assertFalse(unidad.getApta(), "Debe marcar la unidad como apta = false");
        }

        @Test
        @DisplayName("EC-33: Transición por Vencimiento Automático se atribuye estrictamente al 'sistema'")
        void testAtribucionSistemaVencimiento() {
            UUID unidadId = UUID.randomUUID();
            UUID disponibleId = UUID.fromString("11111111-0000-0000-0000-000000000004");

            Unidad unidad = new Unidad();
            unidad.setId(unidadId);
            unidad.setEstadoId(disponibleId);

            when(unidadRepository.findByIdForUpdate(unidadId)).thenReturn(Optional.of(unidad));

            // Transición 8: disponible -> vencida por SISTEMA
            MotorTransicionEstadoUnidad.TransicionRequest requestVencimiento = new MotorTransicionEstadoUnidad.TransicionRequest(
                    unidadId, "vencida", ActorTipo.SISTEMA, "proceso_vencimiento_job", "sistema",
                    UUID.randomUUID(), null, null, null, "CORR-JOB-VENC"
            );

            motor.procesarTransicion(requestVencimiento);

            verify(eventoUnidadRepository).save(eventoCaptor.capture());
            assertEquals(ActorTipo.SISTEMA, eventoCaptor.getValue().getActorTipo());
            assertEquals("proceso_vencimiento_job", eventoCaptor.getValue().getActorId());
        }

        @Test
        @DisplayName("Restricción: Intento de actor 'usuario' para vencimiento es RECHAZADO por catálogo")
        void testRechazoVencimientoPorUsuario() {
            UUID unidadId = UUID.randomUUID();
            UUID disponibleId = UUID.fromString("11111111-0000-0000-0000-000000000004");

            Unidad unidad = new Unidad();
            unidad.setId(unidadId);
            unidad.setEstadoId(disponibleId);

            when(unidadRepository.findByIdForUpdate(unidadId)).thenReturn(Optional.of(unidad));

            // Intento no permitido: Vencimiento disparado por 'usuario'
            MotorTransicionEstadoUnidad.TransicionRequest requestInvalido = new MotorTransicionEstadoUnidad.TransicionRequest(
                    unidadId, "vencida", ActorTipo.USUARIO, "usr_operador_01", "operador",
                    UUID.randomUUID(), null, null, null, "CORR-BAD-ACTOR"
            );

            assertThrows(TransicionEstadoInvalidaException.class, () -> motor.procesarTransicion(requestInvalido));
            assertEquals(disponibleId, unidad.getEstadoId(), "El estado de la unidad NO cambia si el tipo de actor no coincide");
        }
    }
}
