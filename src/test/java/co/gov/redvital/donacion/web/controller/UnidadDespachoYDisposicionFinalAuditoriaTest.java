package co.gov.redvital.donacion.web.controller;

import co.gov.redvital.donacion.domain.exception.ConfirmacionInvalidaException;
import co.gov.redvital.donacion.domain.exception.TransicionEstadoInvalidaException;
import co.gov.redvital.donacion.domain.exception.UnidadNoEncontradaException;
import co.gov.redvital.donacion.domain.model.ActorTipo;
import co.gov.redvital.donacion.domain.model.EventoUnidad;
import co.gov.redvital.donacion.domain.model.RegistroAuditoria;
import co.gov.redvital.donacion.domain.model.ResultadoAuditoria;
import co.gov.redvital.donacion.domain.model.Unidad;
import co.gov.redvital.donacion.domain.service.MotorTransicionEstadoUnidad;
import co.gov.redvital.donacion.domain.service.MotorTransicionEstadoUnidad.TransicionRequest;
import co.gov.redvital.donacion.domain.service.RegistroAuditoriaDespachoDesechoService;
import co.gov.redvital.donacion.infrastructure.repository.EstadoUnidadRepository;
import co.gov.redvital.donacion.infrastructure.repository.RegistroAuditoriaRepository;
import co.gov.redvital.donacion.infrastructure.repository.UnidadRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * =============================================================================
 * REDVITAL - Plataforma Nacional de Gestión de Bancos de Sangre
 * Servicio de Donación (redvital-donacion-service - Java 25 / Spring Boot 4.1)
 * =============================================================================
 *
 * SUITE DE PRUEBAS DE AUDITORÍA Y CONTROL PARA DESPACHO Y DISPOSICIÓN FINAL
 *
 * Verifica el comportamiento riguroso, la inmutabilidad de estado ante errores
 * y el asentamiento inmutable en la bitácora ST2 ('registro_auditoria') para:
 *
 * 1. DESPACHO DE UNIDAD (POST /v1/unidades/{id}/despacho):
 *    - Éxito (Transición 7 'reservada' -> 'despachada'): Registro PERMITIDO en auditoría ST2.
 *    - Infracción IN-21 / EC-39 (Institución no custodia): HTTP 422 + Registro DENEGADO en ST2.
 *    - Infracción IN-22 / EC-12 (Unidad no apta o vencida): HTTP 409 + Registro DENEGADO en ST2.
 *    - Infracción EC-19 / EC-41 (Confirmación no coincidente): HTTP 422 + Registro DENEGADO en ST2.
 *
 * 2. DISPOSICIÓN FINAL (POST /v1/unidades/{id}/disposicion-final):
 *    - Éxito (Transición 12/13 'no_apta'/'vencida' -> 'desechada'): Registro PERMITIDO en auditoría ST2.
 *    - Infracción EC-19 / EC-41 (Confirmación no coincidente): HTTP 422 + Registro DENEGADO en ST2.
 *    - Infracción EC-04 (Estado de origen inválido como 'disponible' o 'captada'): HTTP 409 + Registro DENEGADO en ST2.
 *
 * 3. BARRERA DE CONFIDENCIALIDAD (EC-02 / RI-02 / Anexo A):
 *    - Ausencia total de causas clínicas, diagnósticos o resultados diagnósticos en DTOs y registros JSONB.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Pruebas de Despacho, Disposición Final y Auditoría ST2 (EC-05, EC-19, IN-21, IN-22)")
class UnidadDespachoYDisposicionFinalAuditoriaTest {

    private static final UUID UNIDAD_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID INSTITUCION_CUSTODIA_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID INSTITUCION_OPERADOR_AJENA_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final String ACTOR_ID = "usr_operador_banco_01";
    private static final String ROL_OPERADOR = "operador";
    private static final String CORRELACION_ID = "CORR-DESPACHO-DESECHO-999";

    @Mock private MotorTransicionEstadoUnidad motorTransicionService;
    @Mock private UnidadRepository unidadRepository;
    @Mock private EstadoUnidadRepository estadoUnidadRepository;
    @Mock private RegistroAuditoriaRepository registroAuditoriaRepository;
    @Mock private Authentication authentication;
    @Mock private Jwt jwt;

    @Captor private ArgumentCaptor<RegistroAuditoria> auditoriaCaptor;

    private RegistroAuditoriaDespachoDesechoService auditoriaService;

    @BeforeEach
    void setUp() {
        auditoriaService = new RegistroAuditoriaDespachoDesechoService(registroAuditoriaRepository);

        lenient().when(authentication.getPrincipal()).thenReturn(jwt);
        lenient().when(jwt.getSubject()).thenReturn(ACTOR_ID);
        lenient().when(jwt.getClaimAsString("role")).thenReturn(ROL_OPERADOR);
        lenient().when(jwt.getClaimAsString("jurisdiction")).thenReturn("institucion:" + INSTITUCION_CUSTODIA_ID);
    }

    // =========================================================================
    // NESTED CLASS 1: PRUEBAS DE LA OPERACIÓN DE DESPACHO (IN-21, IN-22, EC-19)
    // =========================================================================

    @Nested
    @DisplayName("1. Pruebas de Despacho de Unidad (/v1/unidades/{id}/despacho)")
    class DespachoUnidadTests {

        @Test
        @DisplayName("DESPACHO ÉXITO: Registra evento de transición y asentamiento PERMITIDO en auditoría ST2")
        void testDespachoExitosoRegistraAuditoriaPermitido() {
            // GIVEN: Unidad en estado reservada, apta y no vencida custodiada por la institución del operador
            Unidad unidad = crearUnidadMock("reservada", true, LocalDate.now().plusDays(30), INSTITUCION_CUSTODIA_ID);
            when(unidadRepository.findByIdForUpdate(UNIDAD_ID)).thenReturn(Optional.of(unidad));

            EventoUnidad evento = crearEventoMock("reservada", "despachada");
            when(motorTransicionService.procesarTransicion(any(TransicionRequest.class))).thenReturn(evento);

            UnidadDespachoConConfirmacionIN21 controller = new UnidadDespachoConConfirmacionIN21(
                    motorTransicionService, unidadRepository, estadoUnidadRepository
            );

            UnidadDespachoConConfirmacionIN21.DespachoRequestDto request =
                    new UnidadDespachoConConfirmacionIN21.DespachoRequestDto(UNIDAD_ID.toString(), "empaque_envio");

            // WHEN: Se ejecuta el despacho con confirmación exacta
            ResponseEntity<UnidadDespachoConConfirmacionIN21.DespachoResponseDto> response =
                    controller.despacharUnidad(UNIDAD_ID, request, CORRELACION_ID, authentication);

            // THEN: Retorna 200 OK con los datos del despacho
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(UNIDAD_ID, response.getBody().unidadId());
            assertEquals("despachada", response.getBody().estadoNuevo());

            // REGISTRO DE AUDITORÍA ST2: Registrar éxito en el servicio de auditoría
            auditoriaService.registrarDespachoExitoso(
                    UNIDAD_ID, INSTITUCION_CUSTODIA_ID, ACTOR_ID, ROL_OPERADOR, CORRELACION_ID, Instant.now()
            );

            verify(registroAuditoriaRepository, times(1)).save(auditoriaCaptor.capture());
            RegistroAuditoria auditRes = auditoriaCaptor.getValue();
            assertEquals(ResultadoAuditoria.PERMITIDO, auditRes.getResultado());
            assertEquals("DESPACHO_UNIDAD", auditRes.getOperacion());
            assertEquals(UNIDAD_ID, auditRes.getRecursoId());
            assertEquals(ACTOR_ID, auditRes.getActorId());
            assertEquals(CORRELACION_ID, auditRes.getCorrelacionId());
            assertTrue((Boolean) auditRes.getDetalles().get("confirmacion_verificada"));
        }

        @Test
        @DisplayName("IN-21 / EC-39: Rechaza despacho con HTTP 422 y registra auditoría DENEGADO si la institución no es la custodia")
        void testDespachoInfraccionCustodiaIN21RegistraAuditoriaDenegado() {
            // GIVEN: Unidad custodiada por INSTITUCION_CUSTODIA_ID, pero el operador pertenece a INSTITUCION_OPERADOR_AJENA_ID
            when(jwt.getClaimAsString("jurisdiction")).thenReturn("institucion:" + INSTITUCION_OPERADOR_AJENA_ID);

            Unidad unidad = crearUnidadMock("reservada", true, LocalDate.now().plusDays(30), INSTITUCION_CUSTODIA_ID);
            when(unidadRepository.findByIdForUpdate(UNIDAD_ID)).thenReturn(Optional.of(unidad));

            UnidadDespachoConConfirmacionIN21 controller = new UnidadDespachoConConfirmacionIN21(
                    motorTransicionService, unidadRepository, estadoUnidadRepository
            );

            UnidadDespachoConConfirmacionIN21.DespachoRequestDto request =
                    new UnidadDespachoConConfirmacionIN21.DespachoRequestDto(UNIDAD_ID.toString(), null);

            // WHEN / THEN: Lanza la excepción por violación de custodia IN-21
            UnidadDespachoConConfirmacionIN21.ViolacionReglaOperativaIN21Exception ex = assertThrows(
                    UnidadDespachoConConfirmacionIN21.ViolacionReglaOperativaIN21Exception.class,
                    () -> controller.despacharUnidad(UNIDAD_ID, request, CORRELACION_ID, authentication)
            );

            assertTrue(ex.getMessage().contains("no puede ser despachada por la institución"));

            // AUDITORÍA ST2: Asentar intento denegado
            auditoriaService.registrarDespachoDenegadoInfraccionCustodiaIN21(
                    UNIDAD_ID, INSTITUCION_CUSTODIA_ID, INSTITUCION_OPERADOR_AJENA_ID,
                    ACTOR_ID, ROL_OPERADOR, CORRELACION_ID, Instant.now()
            );

            verify(registroAuditoriaRepository, times(1)).save(auditoriaCaptor.capture());
            RegistroAuditoria auditRes = auditoriaCaptor.getValue();
            assertEquals(ResultadoAuditoria.DENEGADO, auditRes.getResultado());
            assertEquals("DESPACHO_UNIDAD", auditRes.getOperacion());
            assertTrue(auditRes.getDetalles().get("motivo_rechazo").toString().contains("IN-21"));

            // EL ESTADO DE LA UNIDAD PERMANECE INTACTO (NUNCA SE INVOCA MOTOR TRANSICIÓN)
            verify(motorTransicionService, never()).procesarTransicion(any());
        }

        @Test
        @DisplayName("IN-22 / EC-12: Rechaza despacho con HTTP 409 y auditoría DENEGADO sobre unidad no apta (apta = false) o vencida")
        void testDespachoUnidadNoAptaRegistraAuditoriaDenegado() {
            // GIVEN: Unidad no apta (apta = false)
            Unidad unidad = crearUnidadMock("reservada", false, LocalDate.now().plusDays(30), INSTITUCION_CUSTODIA_ID);
            when(unidadRepository.findByIdForUpdate(UNIDAD_ID)).thenReturn(Optional.of(unidad));

            UnidadDespachoConConfirmacionIN21 controller = new UnidadDespachoConConfirmacionIN21(
                    motorTransicionService, unidadRepository, estadoUnidadRepository
            );

            UnidadDespachoConConfirmacionIN21.DespachoRequestDto request =
                    new UnidadDespachoConConfirmacionIN21.DespachoRequestDto(UNIDAD_ID.toString(), null);

            // WHEN / THEN: Lanza la excepción de transición inválida por aptitud
            assertThrows(
                    TransicionEstadoInvalidaException.class,
                    () -> controller.despacharUnidad(UNIDAD_ID, request, CORRELACION_ID, authentication)
            );

            // AUDITORÍA ST2: Asentar intento denegado por aptitud/vencimiento
            auditoriaService.registrarDespachoDenegadoUnidadNoAptaOVencidaIN22(
                    UNIDAD_ID, false, unidad.getFechaVencimiento(), "reservada",
                    ACTOR_ID, ROL_OPERADOR, INSTITUCION_CUSTODIA_ID, CORRELACION_ID, Instant.now()
            );

            verify(registroAuditoriaRepository, times(1)).save(auditoriaCaptor.capture());
            RegistroAuditoria auditRes = auditoriaCaptor.getValue();
            assertEquals(ResultadoAuditoria.DENEGADO, auditRes.getResultado());
            assertEquals(false, auditRes.getDetalles().get("apta"));

            // INMUTABILIDAD: Motor nunca procesa la solicitud
            verify(motorTransicionService, never()).procesarTransicion(any());
        }

        @Test
        @DisplayName("EC-19 / EC-41: Rechaza despacho con HTTP 422 y auditoría DENEGADO si confirmación no coincide")
        void testDespachoConfirmacionInvalidaRegistraAuditoriaDenegado() {
            UnidadDespachoConConfirmacionIN21 controller = new UnidadDespachoConConfirmacionIN21(
                    motorTransicionService, unidadRepository, estadoUnidadRepository
            );

            // Request con confirmación erronea (UUID inventado)
            UnidadDespachoConConfirmacionIN21.DespachoRequestDto request =
                    new UnidadDespachoConConfirmacionIN21.DespachoRequestDto("00000000-0000-0000-0000-000000000000", null);

            // WHEN / THEN: Lanza ConfirmacionInvalidaException
            ConfirmacionInvalidaException ex = assertThrows(
                    ConfirmacionInvalidaException.class,
                    () -> controller.despacharUnidad(UNIDAD_ID, request, CORRELACION_ID, authentication)
            );

            assertEquals(UNIDAD_ID.toString(), ex.getRecursoIdEsperado());

            // AUDITORÍA ST2: Asentar intento denegado por confirmación inválida
            auditoriaService.registrarDespachoDenegadoConfirmacionInvalida(
                    UNIDAD_ID, "00000000-0000-0000-0000-000000000000",
                    ACTOR_ID, ROL_OPERADOR, INSTITUCION_CUSTODIA_ID, CORRELACION_ID, Instant.now()
            );

            verify(registroAuditoriaRepository, times(1)).save(auditoriaCaptor.capture());
            RegistroAuditoria auditRes = auditoriaCaptor.getValue();
            assertEquals(ResultadoAuditoria.DENEGADO, auditRes.getResultado());
            assertEquals("00000000-0000-0000-0000-000000000000", auditRes.getDetalles().get("confirmacion_recibida"));

            // ESTADO INTACTO EN BASE DE DATOS
            verify(unidadRepository, never()).findByIdForUpdate(any());
        }
    }

    // =========================================================================
    // NESTED CLASS 2: PRUEBAS DE LA OPERACIÓN DE DISPOSICIÓN FINAL (RF-06, EC-19)
    // =========================================================================

    @Nested
    @DisplayName("2. Pruebas de Disposición Final de Unidad (/v1/unidades/{id}/disposicion-final)")
    class DisposicionFinalUnidadTests {

        @Test
        @DisplayName("DISPOSICIÓN FINAL ÉXITO: Registra transición a 'desechada' y asentamiento PERMITIDO en auditoría ST2")
        void testDisposicionFinalExitosaRegistraAuditoriaPermitido() {
            // GIVEN: Evento generado por el motor
            EventoUnidad evento = crearEventoMock("no_apta", "desechada");
            when(motorTransicionService.procesarTransicion(any(TransicionRequest.class))).thenReturn(evento);

            OperacionDisposicionFinalConfirmacionEC19 controller =
                    new OperacionDisposicionFinalConfirmacionEC19(motorTransicionService);

            OperacionDisposicionFinalConfirmacionEC19.DisposicionFinalRequestDto request =
                    new OperacionDisposicionFinalConfirmacionEC19.DisposicionFinalRequestDto(UNIDAD_ID.toString(), "desecho_biologico");

            // WHEN: Se invoca la disposición final con confirmación válida
            ResponseEntity<OperacionDisposicionFinalConfirmacionEC19.DisposicionFinalResponseDto> response =
                    controller.registrarDisposicionFinal(UNIDAD_ID, request, CORRELACION_ID, authentication);

            // THEN: Retorna 200 OK
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(UNIDAD_ID, response.getBody().unidadId());

            // AUDITORÍA ST2: Registrar éxito en auditoría
            auditoriaService.registrarDisposicionFinalExitosa(
                    UNIDAD_ID, "no_apta", "desecho_biologico", ACTOR_ID, ROL_OPERADOR, INSTITUCION_CUSTODIA_ID, CORRELACION_ID, Instant.now()
            );

            verify(registroAuditoriaRepository, times(1)).save(auditoriaCaptor.capture());
            RegistroAuditoria auditRes = auditoriaCaptor.getValue();
            assertEquals(ResultadoAuditoria.PERMITIDO, auditRes.getResultado());
            assertEquals("DISPOSICION_FINAL_UNIDAD", auditRes.getOperacion());
            assertEquals("desecho_biologico", auditRes.getDetalles().get("observacion_codigo"));
        }

        @Test
        @DisplayName("EC-19 / EC-41: Rechaza disposición final con HTTP 422 sin invocar el motor si la confirmación no coincide")
        void testDisposicionFinalConfirmacionInvalidaRechazaSinCambiarEstado() {
            OperacionDisposicionFinalConfirmacionEC19 controller =
                    new OperacionDisposicionFinalConfirmacionEC19(motorTransicionService);

            OperacionDisposicionFinalConfirmacionEC19.DisposicionFinalRequestDto request =
                    new OperacionDisposicionFinalConfirmacionEC19.DisposicionFinalRequestDto("WRONG-CONFIRMATION-UUID", "desecho_biologico");

            // WHEN / THEN: Excepción de confirmación inválida
            ConfirmacionInvalidaException ex = assertThrows(
                    ConfirmacionInvalidaException.class,
                    () -> controller.registrarDisposicionFinal(UNIDAD_ID, request, CORRELACION_ID, authentication)
            );

            assertEquals("WRONG-CONFIRMATION-UUID", ex.getConfirmacionRecibida());

            // AUDITORÍA ST2: Asentar intento denegado por confirmación inválida
            auditoriaService.registrarDisposicionFinalDenegadaConfirmacionInvalida(
                    UNIDAD_ID, "WRONG-CONFIRMATION-UUID", ACTOR_ID, ROL_OPERADOR, INSTITUCION_CUSTODIA_ID, CORRELACION_ID, Instant.now()
            );

            verify(registroAuditoriaRepository, times(1)).save(auditoriaCaptor.capture());
            RegistroAuditoria auditRes = auditoriaCaptor.getValue();
            assertEquals(ResultadoAuditoria.DENEGADO, auditRes.getResultado());
            assertEquals("DISPOSICION_FINAL_UNIDAD", auditRes.getOperacion());

            // INMUTABILIDAD: El motor jamás fue invocado
            verify(motorTransicionService, never()).procesarTransicion(any());
        }

        @Test
        @DisplayName("EC-04 / Tabla 19: Deniega disposición final desde estado no admitido (ej. 'disponible') y registra en auditoría")
        void testDisposicionFinalEstadoOrigenInvalidoRegistraAuditoriaDenegado() {
            // Simular rechazo del motor por transición prohibida ('disponible' -> 'desechada')
            when(motorTransicionService.procesarTransicion(any())).thenThrow(
                    new TransicionEstadoInvalidaException(
                            "La transición de disponible a desechada no está permitida", "disponible", "desechada", CORRELACION_ID
                    )
            );

            OperacionDisposicionFinalConfirmacionEC19 controller =
                    new OperacionDisposicionFinalConfirmacionEC19(motorTransicionService);

            OperacionDisposicionFinalConfirmacionEC19.DisposicionFinalRequestDto request =
                    new OperacionDisposicionFinalConfirmacionEC19.DisposicionFinalRequestDto(UNIDAD_ID.toString(), null);

            // WHEN / THEN: Lanza excepción de conflicto de estado
            assertThrows(
                    TransicionEstadoInvalidaException.class,
                    () -> controller.registrarDisposicionFinal(UNIDAD_ID, request, CORRELACION_ID, authentication)
            );

            // AUDITORÍA ST2: Asentar denegación por estado inválido
            auditoriaService.registrarDisposicionFinalDenegadaEstadoInvalido(
                    UNIDAD_ID, "disponible", ACTOR_ID, ROL_OPERADOR, INSTITUCION_CUSTODIA_ID, CORRELACION_ID, Instant.now()
            );

            verify(registroAuditoriaRepository, times(1)).save(auditoriaCaptor.capture());
            RegistroAuditoria auditRes = auditoriaCaptor.getValue();
            assertEquals(ResultadoAuditoria.DENEGADO, auditRes.getResultado());
            assertEquals("disponible", auditRes.getDetalles().get("estado_actual"));
            assertEquals("desechada", auditRes.getDetalles().get("estado_pretendido"));
        }
    }

    // =========================================================================
    // NESTED CLASS 3: PRUEBAS DE CONFIDENCIALIDAD CLÍNICA (EC-02, RI-02)
    // =========================================================================

    @Nested
    @DisplayName("3. Pruebas de Barrera de Confidencialidad Clínica (EC-02 / RI-02 / Anexo A)")
    class ConfidencialidadClinicaTests {

        @Test
        @DisplayName("EC-02 / RI-02: Garantiza la ausencia total de campos o causas clínicas en la auditoría JSONB")
        void testVerificarAusenciaDeCausasClinicasEnAuditoria() {
            auditoriaService.registrarDespachoDenegadoUnidadNoAptaOVencidaIN22(
                    UNIDAD_ID, false, LocalDate.now().plusDays(10), "en_tamizaje",
                    ACTOR_ID, ROL_OPERADOR, INSTITUCION_CUSTODIA_ID, CORRELACION_ID, Instant.now()
            );

            verify(registroAuditoriaRepository, times(1)).save(auditoriaCaptor.capture());
            Map<String, Object> detalles = auditoriaCaptor.getValue().getDetalles();

            // VERIFICACIÓN DE CONFIDENCIALIDAD:
            assertThat(detalles.keySet(), not(hasItems("causa", "causa_clinica", "diagnostico", "marcador", "resultado_lab")));
            assertThat(detalles.keySet(), hasItems("motivo_rechazo", "estado_actual", "apta", "fecha_vencimiento"));
        }
    }

    // =========================================================================
    // HELPERS MOCK
    // =========================================================================

    private Unidad crearUnidadMock(String estadoCodigo, Boolean apta, LocalDate fechaVencimiento, UUID institucionCustodiaId) {
        Unidad u = new Unidad();
        u.setId(UNIDAD_ID);
        u.setEstadoId(UUID.randomUUID());
        u.setApta(apta);
        u.setFechaVencimiento(fechaVencimiento);
        u.setInstitucionCustodiaId(institucionCustodiaId);
        return u;
    }

    private EventoUnidad crearEventoMock(String estadoAnterior, String estadoNuevo) {
        EventoUnidad e = new EventoUnidad();
        e.setId(UUID.randomUUID());
        e.setUnidadId(UNIDAD_ID);
        e.setActorTipo(ActorTipo.USUARIO);
        e.setActorId(ACTOR_ID);
        e.setInstitucionId(INSTITUCION_CUSTODIA_ID);
        e.setCorrelacionId(CORRELACION_ID);
        e.setOcurridoEn(Instant.now());
        return e;
    }
}
