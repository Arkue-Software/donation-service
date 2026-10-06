package co.gov.redvital.donacion.web.controller;

import co.gov.redvital.donacion.domain.exception.TransicionEstadoInvalidaException;
import co.gov.redvital.donacion.domain.exception.UnidadNoEncontradaException;
import co.gov.redvital.donacion.domain.model.ActorTipo;
import co.gov.redvital.donacion.domain.model.EventoUnidad;
import co.gov.redvital.donacion.domain.service.MotorTransicionEstadoUnidad;
import co.gov.redvital.donacion.domain.service.MotorTransicionEstadoUnidad.TransicionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Suite de Pruebas de Integración Web (@WebMvcTest) para UnidadTamizajeVeredictoController.
 *
 * Cubre de forma rigurosa los escenarios de tamizaje (RF-05, EC-02, RI-02, EC-04, EC-36):
 * 1. Veredicto Apto (apta = true) -> Transición 3 ('en_tamizaje' -> 'disponible', USUARIO).
 * 2. Veredicto No Apto (apta = false) -> Transición 4 ('en_tamizaje' -> 'no_apta', USUARIO).
 * 3. Barrera Estricta de Esquema (EC-02, RI-02, Anexo A): Rechazo HTTP 400 Bad Request si la petición
 *    contiene campos no permitidos (p. ej., causa, diagnostico, resultado_lab).
 * 4. Manejo de Transición Inválida (Unidad no en tamizaje) -> HTTP 409 Conflict (RFC 9457).
 * 5. Manejo de Recurso No Encontrado -> HTTP 404 Not Found.
 * 6. Control de Acceso por Rol (U3 - Operador de Banco).
 */
@WebMvcTest(UnidadTamizajeVeredictoController.class)
@DisplayName("Pruebas del Controlador de Tamizaje (POST /v1/unidades/{id}/tamizaje)")
class UnidadTamizajeVeredictoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MotorTransicionEstadoUnidad motorTransicionService;

    private UUID unidadId;
    private UUID institucionId;
    private String actorId;
    private Jwt jwtOperador;

    @BeforeEach
    void setUp() {
        unidadId = UUID.randomUUID();
        institucionId = UUID.randomUUID();
        actorId = "operador-sub-12345";

        jwtOperador = Jwt.withTokenValue("mock-jwt-token")
                .header("alg", "RS256")
                .subject(actorId)
                .claim("role", "operador")
                .claim("jurisdiction", "institucion:" + institucionId)
                .build();
    }

    @Nested
    @DisplayName("Escenarios Exitosos de Veredicto (RF-05)")
    class EscenariosExitosos {

        @Test
        @DisplayName("Debe registrar veredicto APTO (apta=true) y transicionar a 'disponible' (HTTP 200 OK)")
        void testRegistrarVeredictoAptoExitoso() throws Exception {
            EventoUnidad eventoMock = crearEventoMock(unidadId, "disponible", true);
            when(motorTransicionService.procesarTransicion(any(TransicionRequest.class))).thenReturn(eventoMock);

            String requestJson = """
                {
                    "apta": true,
                    "observacionCodigo": "control_temperatura"
                }
                """;

            mockMvc.perform(post("/v1/unidades/{id}/tamizaje", unidadId)
                            .with(jwt().jwt(jwtOperador).authorities(new SimpleGrantedAuthority("ROLE_operador")))
                            .header("X-Correlacion-Id", "CORR-TAMIZAJE-001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unidadId").value(unidadId.toString()))
                    .andExpect(jsonPath("$.estadoAnterior").value("en_tamizaje"))
                    .andExpect(jsonPath("$.estadoNuevo").value("disponible"))
                    .andExpect(jsonPath("$.apta").value(true))
                    .andExpect(jsonPath("$.actorId").value(actorId))
                    .andExpect(jsonPath("$.actorTipo").value("USUARIO"))
                    .andExpect(jsonPath("$.correlacionId").value("CORR-TAMIZAJE-001"));

            ArgumentCaptor<TransicionRequest> captor = ArgumentCaptor.forClass(TransicionRequest.class);
            verify(motorTransicionService).procesarTransicion(captor.capture());
            TransicionRequest req = captor.getValue();

            assertEquals(unidadId, req.unidadId());
            assertEquals("disponible", req.estadoDestinoCodigo());
            assertEquals(ActorTipo.USUARIO, req.actorTipo());
            assertEquals(actorId, req.actorId());
            assertEquals(institucionId, req.institucionId());
            assertEquals("control_temperatura", req.observacionCodigo());
            assertTrue(req.veredictoApta());
        }

        @Test
        @DisplayName("Debe registrar veredicto NO APTO (apta=false) y transicionar a 'no_apta' (HTTP 200 OK)")
        void testRegistrarVeredictoNoAptoExitoso() throws Exception {
            EventoUnidad eventoMock = crearEventoMock(unidadId, "no_apta", false);
            when(motorTransicionService.procesarTransicion(any(TransicionRequest.class))).thenReturn(eventoMock);

            String requestJson = """
                {
                    "apta": false
                }
                """;

            mockMvc.perform(post("/v1/unidades/{id}/tamizaje", unidadId)
                            .with(jwt().jwt(jwtOperador).authorities(new SimpleGrantedAuthority("ROLE_operador")))
                            .header("X-Correlacion-Id", "CORR-TAMIZAJE-002")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unidadId").value(unidadId.toString()))
                    .andExpect(jsonPath("$.estadoAnterior").value("en_tamizaje"))
                    .andExpect(jsonPath("$.estadoNuevo").value("no_apta"))
                    .andExpect(jsonPath("$.apta").value(false));

            ArgumentCaptor<TransicionRequest> captor = ArgumentCaptor.forClass(TransicionRequest.class);
            verify(motorTransicionService).procesarTransicion(captor.capture());
            assertEquals("no_apta", captor.getValue().estadoDestinoCodigo());
            assertFalse(captor.getValue().veredictoApta());
        }
    }

    @Nested
    @DisplayName("Barrera de Confidencialidad y Esquema Estricto (EC-02, RI-02, DD Sec 14.4)")
    class BarreraConfidencialidad {

        @Test
        @DisplayName("Debe RECHAZAR con HTTP 400 Bad Request si el JSON incluye campos no permitidos (ej. 'causa' o 'diagnostico')")
        void testRechazoCampoCausaClinicaInyectado() throws Exception {
            // Intento de inyección de causa clínica en la petición HTTP
            String jsonConCausaClinica = """
                {
                    "apta": false,
                    "causa": "Reactivo VIH - Prueba confirmativa Western Blot",
                    "diagnostico": "Infeccioso"
                }
                """;

            mockMvc.perform(post("/v1/unidades/{id}/tamizaje", unidadId)
                            .with(jwt().jwt(jwtOperador).authorities(new SimpleGrantedAuthority("ROLE_operador")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonConCausaClinica))
                    .andExpect(status().isBadRequest());

            // Garantiza que la petición NUNCA llegó al motor de servicio
            verify(motorTransicionService, never()).procesarTransicion(any());
        }

        @Test
        @DisplayName("Debe RECHAZAR con HTTP 400 Bad Request si la propiedad 'apta' no se incluye")
        void testRechazoBanderaAptaAusente() throws Exception {
            String jsonSinApta = """
                {
                    "observacionCodigo": "control_temperatura"
                }
                """;

            mockMvc.perform(post("/v1/unidades/{id}/tamizaje", unidadId)
                            .with(jwt().jwt(jwtOperador).authorities(new SimpleGrantedAuthority("ROLE_operador")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonSinApta))
                    .andExpect(status().isBadRequest());

            verify(motorTransicionService, never()).procesarTransicion(any());
        }
    }

    @Nested
    @DisplayName("Escenarios de Conflicto y Error (HTTP 409 / 404 / 403)")
    class EscenariosError {

        @Test
        @DisplayName("Debe retornar HTTP 409 Conflict cuando la unidad no está en estado 'en_tamizaje' (RFC 9457)")
        void testErrorTransicionInvalida() throws Exception {
            when(motorTransicionService.procesarTransicion(any()))
                    .thenThrow(new TransicionEstadoInvalidaException(
                            "La transición del estado 'captada' al estado 'disponible' no está permitida.",
                            "captada",
                            "disponible",
                            "CORR-ERR-409"
                    ));

            String requestJson = """
                {
                    "apta": true
                }
                """;

            mockMvc.perform(post("/v1/unidades/{id}/tamizaje", unidadId)
                            .with(jwt().jwt(jwtOperador).authorities(new SimpleGrantedAuthority("ROLE_operador")))
                            .header("X-Correlacion-Id", "CORR-ERR-409")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://redvital.gov.co/errores/conflicto-de-estado"))
                    .andExpect(jsonPath("$.title").value("Transición de Tamizaje No Permitida"))
                    .andExpect(jsonPath("$.estado_actual").value("captada"))
                    .andExpect(jsonPath("$.estado_pretendido").value("disponible"))
                    .andExpect(jsonPath("$.correlacion_id").value("CORR-ERR-409"));
        }

        @Test
        @DisplayName("Debe retornar HTTP 404 Not Found si la unidad no existe")
        void testErrorUnidadNoEncontrada() throws Exception {
            when(motorTransicionService.procesarTransicion(any()))
                    .thenThrow(new UnidadNoEncontradaException(unidadId, "CORR-ERR-404"));

            String requestJson = """
                {
                    "apta": true
                }
                """;

            mockMvc.perform(post("/v1/unidades/{id}/tamizaje", unidadId)
                            .with(jwt().jwt(jwtOperador).authorities(new SimpleGrantedAuthority("ROLE_operador")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("https://redvital.gov.co/errores/no-encontrado"))
                    .andExpect(jsonPath("$.title").value("Unidad No Encontrada"));
        }

        @Test
        @DisplayName("Debe retornar HTTP 403 Forbidden si el usuario no posee el rol de operador")
        void testErrorAccesoDenegadoPorRol() throws Exception {
            Jwt jwtDonante = Jwt.withTokenValue("mock-jwt-donante")
                    .header("alg", "RS256")
                    .subject("donante-123")
                    .claim("role", "donante")
                    .build();

            String requestJson = """
                {
                    "apta": true
                }
                """;

            mockMvc.perform(post("/v1/unidades/{id}/tamizaje", unidadId)
                            .with(jwt().jwt(jwtDonante).authorities(new SimpleGrantedAuthority("ROLE_donante")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isForbidden());

            verify(motorTransicionService, never()).procesarTransicion(any());
        }
    }

    private EventoUnidad crearEventoMock(UUID uId, String estadoNuevo, boolean apta) {
        EventoUnidad evento = new EventoUnidad();
        evento.setId(UUID.randomUUID());
        evento.setUnidadId(uId);
        evento.setActorTipo(ActorTipo.USUARIO);
        evento.setActorId(actorId);
        evento.setInstitucionId(institucionId);
        evento.setCorrelacionId("CORR-TAMIZAJE-001");
        evento.setOcurridoEn(Instant.now());
        return evento;
    }
}
