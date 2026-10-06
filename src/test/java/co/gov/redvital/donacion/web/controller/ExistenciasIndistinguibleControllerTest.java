package co.gov.redvital.donacion.web.controller;

import co.gov.redvital.donacion.web.controller.RespuestaIndistinguibleInstitucionAjenaDS04.ExistenciasComponenteTipoSanguineoDto;
import co.gov.redvital.donacion.web.controller.RespuestaIndistinguibleInstitucionAjenaDS04.ExistenciasIndistinguibleController;
import co.gov.redvital.donacion.web.controller.RespuestaIndistinguibleInstitucionAjenaDS04.ExistenciasIndistinguibleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * =============================================================================
 * REDVITAL - Plataforma Nacional de Gestión de Bancos de Sangre
 * Servicio de Donación (redvital-donacion-service - Java 25 / Spring Boot 4.1)
 * =============================================================================
 *
 * SUITE DE PRUEBAS PARA LA CONSULTA DE EXISTENCIAS CON RESPUESTA INDISTINGUIBLE
 * ANTE INSTITUCIÓN AJENA Y REGISTRO DE AUDITORÍA (ST2)
 *
 * Escenarios probados (conforme a DD V2.0 Sec. 12.1, SAD V2.0 EC-01, EC-05, EC-06):
 * 1. Consulta autorizada sobre la institución custodia del token (devuelve existencias 200 OK).
 * 2. Consulta implícita sin parámetro 'institucion_id' (resuelve institución del token).
 * 3. Consulta sobre INSTITUCIÓN AJENA (EC-01): Devuelve HTTP 200 OK con arreglo vacío '[]'
 *    (RESPUESTA INDISTINGUIBLE de 0 existencias / institución inexistente, evitando enumeración EC-06).
 * 4. Verificación del Registro de Auditoría (ST2): Confirma la invocación inmutable
 *    de 'registrarIntentoAccesoDenegadoJurisdiccion' ante consulta ajena.
 * 5. Control de Acceso por Rol (PreAuthorize): Bloquea con HTTP 403 roles no autorizados.
 */
@WebMvcTest(ExistenciasIndistinguibleController.class)
@DisplayName("Pruebas de Existencias con Respuesta Indistinguible ante Institución Ajena (DS-04 / EC-01)")
class ExistenciasIndistinguibleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ExistenciasIndistinguibleService existenciasService;

    private static final UUID INSTITUCION_TOKEN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID INSTITUCION_AJENA_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID COMPONENTE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID GRUPO_SANGUINEO_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String ACTOR_ID = "usr_operador_banco_01";
    private static final String ROL_OPERADOR = "operador";

    private Jwt jwtOperador;

    @BeforeEach
    void setUp() {
        jwtOperador = Jwt.withTokenValue("mock-jwt-token")
                .header("alg", "RS256")
                .subject(ACTOR_ID)
                .claim("role", ROL_OPERADOR)
                .claim("jurisdiction", "institucion:" + INSTITUCION_TOKEN_ID)
                .build();
    }

    @Nested
    @DisplayName("1. Consultas Autorizadas sobre Institución Propia")
    class ConsultasAutorizadas {

        @Test
        @DisplayName("Debe retornar existencias agregadas 200 OK cuando la institución del parámetro coincide con la del token")
        void testConsultaInstitucionPropiaExitosa() throws Exception {
            ExistenciasComponenteTipoSanguineoDto dto = new ExistenciasComponenteTipoSanguineoDto(
                    INSTITUCION_TOKEN_ID,
                    COMPONENTE_ID,
                    GRUPO_SANGUINEO_ID,
                    15L,
                    LocalDate.now().plusDays(20)
            );

            when(existenciasService.consultarExistenciasPorInventarioYFiltros(
                    eq(INSTITUCION_TOKEN_ID), any(), any(), any()))
                    .thenReturn(List.of(dto));

            mockMvc.perform(get("/v1/inventario/existencias-institucion")
                            .param("institucion_id", INSTITUCION_TOKEN_ID.toString())
                            .header("X-Correlacion-Id", "CORR-EXP-001")
                            .with(jwt().jwt(jwtOperador).authorities(new SimpleGrantedAuthority("ROLE_operador"))))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].institucionId", is(INSTITUCION_TOKEN_ID.toString())))
                    .andExpect(jsonPath("$[0].componenteId", is(COMPONENTE_ID.toString())))
                    .andExpect(jsonPath("$[0].grupoSanguineoId", is(GRUPO_SANGUINEO_ID.toString())))
                    .andExpect(jsonPath("$[0].cantidadDisponible", is(15)));

            // Verificación: NO debe registrar auditoría de intento denegado
            verify(existenciasService, never()).registrarIntentoAccesoDenegadoJurisdiccion(any(), any(), any(), any());
            verify(existenciasService, times(1)).consultarExistenciasPorInventarioYFiltros(
                    eq(INSTITUCION_TOKEN_ID), any(), eq(null), eq(null));
        }

        @Test
        @DisplayName("Debe resolver automáticamente la institución del token cuando no se pasa 'institucion_id'")
        void testConsultaImplicitapropiaSinParametro() throws Exception {
            when(existenciasService.consultarExistenciasPorInventarioYFiltros(
                    eq(INSTITUCION_TOKEN_ID), any(), eq(COMPONENTE_ID), eq(GRUPO_SANGUINEO_ID)))
                    .thenReturn(List.of());

            mockMvc.perform(get("/v1/inventario/existencias-institucion")
                            .param("componente_id", COMPONENTE_ID.toString())
                            .param("grupo_sanguineo_id", GRUPO_SANGUINEO_ID.toString())
                            .with(jwt().jwt(jwtOperador).authorities(new SimpleGrantedAuthority("ROLE_operador"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));

            verify(existenciasService, never()).registrarIntentoAccesoDenegadoJurisdiccion(any(), any(), any(), any());
            verify(existenciasService, times(1)).consultarExistenciasPorInventarioYFiltros(
                    eq(INSTITUCION_TOKEN_ID), any(), eq(COMPONENTE_ID), eq(GRUPO_SANGUINEO_ID));
        }
    }

    @Nested
    @DisplayName("2. Respuesta Indistinguible y Auditoría ante Institución Ajena (EC-01 / ST2)")
    class RespuestaIndistinguibleEAuditoria {

        @Test
        @DisplayName("EC-01 / EC-06: Debe retornar HTTP 200 OK con arreglo vacío [] ante consulta a institución ajena")
        void testConsultaInstitucionAjenaRetornaRespuestaIndistinguible() throws Exception {
            String correlacionId = "CORR-AJENA-101";

            mockMvc.perform(get("/v1/inventario/existencias-institucion")
                            .param("institucion_id", INSTITUCION_AJENA_ID.toString())
                            .header("X-Correlacion-Id", correlacionId)
                            .with(jwt().jwt(jwtOperador).authorities(new SimpleGrantedAuthority("ROLE_operador"))))
                    .andExpect(status().isOk()) // JAMÁS HTTP 403 que confirme la existencia de la institución
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$", hasSize(0))); // Respuesta idéntica a 0 existencias

            // VERIFICACIÓN CLAVE DE SEGURIDAD (ST2 / EC-05):
            // 1. Asienta inmutablemente el intento denegado en registro_auditoria
            verify(existenciasService, times(1)).registrarIntentoAccesoDenegadoJurisdiccion(
                    eq(ACTOR_ID),
                    eq(ROL_OPERADOR),
                    eq(INSTITUCION_AJENA_ID),
                    eq(correlacionId)
            );

            // 2. NUNCA consulta el repositorio de existencias para la institución ajena
            verify(existenciasService, never()).consultarExistenciasPorInventarioYFiltros(
                    eq(INSTITUCION_AJENA_ID), any(), any(), any());
        }

        @Test
        @DisplayName("ST2: Genera identificador de correlación automático si no viene la cabecera X-Correlacion-Id")
        void testGeneraCorrelacionIdSiFaltaCabecera() throws Exception {
            mockMvc.perform(get("/v1/inventario/existencias-institucion")
                            .param("institucion_id", INSTITUCION_AJENA_ID.toString())
                            .with(jwt().jwt(jwtOperador).authorities(new SimpleGrantedAuthority("ROLE_operador"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));

            verify(existenciasService, times(1)).registrarIntentoAccesoDenegadoJurisdiccion(
                    eq(ACTOR_ID),
                    eq(ROL_OPERADOR),
                    eq(INSTITUCION_AJENA_ID),
                    argThat(corr -> corr != null && corr.startsWith("CORR-"))
            );
        }
    }

    @Nested
    @DisplayName("3. Control de Acceso y Autorización")
    class ControlAcceso {

        @Test
        @DisplayName("Debe retornar HTTP 403 Forbidden cuando el rol del usuario no está autorizado (ej. donante)")
        void testRolNoAutorizadoRetorna403() throws Exception {
            Jwt jwtDonante = Jwt.withTokenValue("mock-jwt-donante")
                    .header("alg", "RS256")
                    .subject("usr_donante_99")
                    .claim("role", "donante")
                    .claim("jurisdiction", "nacional")
                    .build();

            mockMvc.perform(get("/v1/inventario/existencias-institucion")
                            .with(jwt().jwt(jwtDonante).authorities(new SimpleGrantedAuthority("ROLE_donante"))))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(existenciasService);
        }

        @Test
        @DisplayName("Debe permitir el acceso al perfil 'admin_banco'")
        void testRolAdminBancoPermitido() throws Exception {
            Jwt jwtAdmin = Jwt.withTokenValue("mock-jwt-admin")
                    .header("alg", "RS256")
                    .subject("usr_admin_banco_02")
                    .claim("role", "admin_banco")
                    .claim("jurisdiction", "institucion:" + INSTITUCION_TOKEN_ID)
                    .build();

            mockMvc.perform(get("/v1/inventario/existencias-institucion")
                            .with(jwt().jwt(jwtAdmin).authorities(new SimpleGrantedAuthority("ROLE_admin_banco"))))
                    .andExpect(status().isOk());

            verify(existenciasService, times(1)).consultarExistenciasPorInventarioYFiltros(
                    eq(INSTITUCION_TOKEN_ID), any(), any(), any());
        }
    }
}
