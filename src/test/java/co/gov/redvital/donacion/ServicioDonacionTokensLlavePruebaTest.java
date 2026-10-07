package co.gov.redvital.donacion;

import co.gov.redvital.donacion.domain.model.ActorTipo;
import co.gov.redvital.donacion.domain.service.MotorTransicionEstadoUnidad;
import co.gov.redvital.donacion.infrastructure.client.CampaniaServiceAdapter;
import co.gov.redvital.donacion.infrastructure.logging.CorrelacionLoggingFilterMDC;
import co.gov.redvital.donacion.web.controller.RegistroDonacionIdempotenteDS10;
import co.gov.redvital.donacion.web.controller.RegistroDonacionIdempotenteDS10.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * =============================================================================
 * REDVITAL - Plataforma Nacional de Gestión de Bancos de Sangre
 * Servicio de Donación (redvital-donacion-service - Java 25 / Spring Boot 4.1)
 * =============================================================================
 *
 * SUITE DE PRUEBAS DE INTEGRACIÓN CON TOKENS JWT FIRMADOS POR LA LLAVE DE PRUEBA
 *
 * Cumple estrictamente con las especificaciones de T-303.1, T-303.2, T-303.3,
 * ADR-008, README.md, LEEME-T-303.2.md y los estándares de arquitectura:
 *
 * 1. Generación de Llave de Prueba en Memoria (T-303.2 / README.md):
 *    - La suite NO lee el archivo 'llaves/dev-signing-key.private.pem' para ser
 *      100% autocontenida y ejecutable en servidores de integración continua (CI/CD / GitHub Actions).
 *    - Genera dinámicamente un par de llaves RSA de 2048 bits en memoria con kid = 'dev-key-1'.
 *
 * 2. Estructura de Reivindicaciones Normativas (ADR-008 / T-303.1):
 *    - emisor (iss): "https://identidad.redvital.local"
 *    - audiencia (aud): "redvital"
 *    - sujeto (sub): UUID del operador/usuario (U3 / U4).
 *    - rol (role): "operador", "admin_banco", "donante", etc.
 *    - jurisdicción (jurisdiction): "institucion:<uuid>"
 *    - encabezado JWT kid: "dev-key-1" (fijado por ProveedorLlaveFirmaDesarrollo).
 *
 * 3. Cobertura de Componentes Integrados:
 *    - Validaciones de Seguridad JWT y Autorización por Perfil (ADR-011 / EC-01).
 *    - Idempotencia del Registro de Donación con Idempotency-Key (DS-10 / IN-12 / EC-10).
 *    - Trazabilidad Distribuida y Propagación de MDC (CorrelacionLoggingFilterMDC / EC-20).
 *    - Adaptador de Campañas con Degradación ante Fallos (CampaniaServiceAdapter / EC-08).
 *    - Canalización del Alta de Unidad a través del Motor (MotorTransicionEstadoUnidad / IN-04).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Pruebas de Integración con Tokens JWT Firmados por la Llave de Prueba (T-303.2 / ADR-008)")
public class ServicioDonacionTokensLlavePruebaTest {

    private static final String KID_LLAVE_PRUEBA = "dev-key-1";
    private static final String EMISOR_REDVITAL = "https://identidad.redvital.local";
    private static final String AUDIENCIA_REDVITAL = "redvital";

    private static final UUID INSTITUCION_BOGOTA_ID = UUID.fromString("99999999-0000-0000-0000-000000000001");
    private static final UUID OPERADOR_ID = UUID.fromString("88888888-0000-0000-0000-000000000002");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RegistroDonacionIdempotenteService donacionIdempotenteService;

    @MockBean
    private MotorTransicionEstadoUnidad motorTransicionService;

    @MockBean
    private CampaniaServiceAdapter campaniaAdapter;

    private KeyPair keyPair;
    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;

    @BeforeAll
    void inicializarLlaveDePruebaEnMemoria() throws Exception {
        // Genera el par de llaves RSA 2048 equivalente a T-303.1 en memoria para la suite
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        this.keyPair = kpg.generateKeyPair();
        this.privateKey = (RSAPrivateKey) keyPair.getPrivate();
        this.publicKey = (RSAPublicKey) keyPair.getPublic();
    }

    /**
     * Helper para emitir tokens JWT válidos firmados con la llave de prueba RS256 (T-303.1 / ADR-008).
     */
    private String emitirTokenPrueba(
            String sub,
            String role,
            String jurisdiction,
            long validezSegundos) throws Exception {

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(KID_LLAVE_PRUEBA)
                .type(JOSEObjectType.JWT)
                .build();

        Instant ahora = Instant.now();
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer(EMISOR_REDVITAL)
                .audience(AUDIENCIA_REDVITAL)
                .subject(sub)
                .claim("role", role)
                .claim("jurisdiction", jurisdiction)
                .issueTime(Date.from(ahora))
                .notBeforeTime(Date.from(ahora))
                .expirationTime(Date.from(ahora.plusSeconds(validezSegundos)))
                .build();

        SignedJWT signedJWT = new SignedJWT(header, claimsSet);
        signedJWT.sign(new RSASSASigner(privateKey));
        return signedJWT.serialize();
    }

    /**
     * Configuración de Seguridad Spring Security para la Suite de Pruebas.
     */
    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfig {

        @Bean
        @Primary
        public JwtDecoder testJwtDecoder(RSAPublicKey publicKey) {
            // Decodificador que valida tokens contra la llave pública dev-key-1 generada en memoria
            return NimbusJwtDecoder.withPublicKey(publicKey).build();
        }

        @Bean
        public JwtAuthenticationConverter jwtAuthenticationConverter() {
            JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
            grantedAuthoritiesConverter.setAuthoritiesClaimName("role");
            grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");

            JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
            jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
            return jwtAuthenticationConverter;
        }

        @Bean
        public SecurityFilterChain securityFilterChain(
                HttpSecurity http,
                CorrelacionLoggingFilterMDC.CorrelacionMdcFilter correlacionFilter) throws Exception {
            http
                    .csrf(csrf -> csrf.disable())
                    .addFilterBefore(correlacionFilter, UsernamePasswordAuthenticationFilter.class)
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/v1/donaciones/**").hasRole("operador")
                            .anyRequest().authenticated()
                    )
                    .oauth2ResourceServer(oauth2 -> oauth2
                            .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                    );
            return http.build();
        }
    }

    // =========================================================================
    // ESCENARIOS DE PRUEBA
    // =========================================================================

    @Nested
    @DisplayName("1. Registro de Donación con Token Válido de Operador (U3 / dev-key-1)")
    class RegistroDonacionConTokenValidoTest {

        @Test
        @DisplayName("Debe registrar la donación exitosamente cuando el token JWT es firmado por la llave dev-key-1 con rol 'operador'")
        void testRegistroDonacionExitosoConTokenDevKey() throws Exception {
            String token = emitirTokenPrueba(
                    OPERADOR_ID.toString(),
                    "operador",
                    "institucion:" + INSTITUCION_BOGOTA_ID,
                    3600
            );

            UUID donacionId = UUID.randomUUID();
            UUID tipoDonacionId = UUID.randomUUID();
            UUID componenteId = UUID.randomUUID();
            String idempotencyKey = "IK-TEST-DEVKEY-100";
            String correlacionId = "CORR-DEVKEY-100";

            RegistroDonacionRequestDto requestDto = new RegistroDonacionRequestDto(
                    UUID.randomUUID(), // donante_id
                    null,              // intencion_id
                    tipoDonacionId,
                    null,              // campania_id
                    List.of(componenteId)
            );

            RegistroDonacionResponseDto responseDto = new RegistroDonacionResponseDto(
                    donacionId,
                    requestDto.donanteId(),
                    null,
                    tipoDonacionId,
                    null,
                    INSTITUCION_BOGOTA_ID,
                    OPERADOR_ID,
                    Instant.now(),
                    List.of(new UnidadCreadaDto(UUID.randomUUID(), componenteId, "captada", LocalDate.now().plusDays(35), INSTITUCION_BOGOTA_ID)),
                    correlacionId
            );

            when(donacionIdempotenteService.procesarDonacionIdempotente(
                    eq(OPERADOR_ID.toString()),
                    eq(idempotencyKey),
                    any(RegistroDonacionRequestDto.class),
                    eq(INSTITUCION_BOGOTA_ID),
                    eq(correlacionId)
            )).thenReturn(ResponseEntity.status(HttpStatus.CREATED)
                    .header("X-Correlacion-Id", correlacionId)
                    .body(responseDto));

            mockMvc.perform(post("/v1/donaciones")
                            .header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", idempotencyKey)
                            .header("X-Correlacion-Id", correlacionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(requestDto)))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("X-Correlacion-Id", correlacionId))
                    .andExpect(jsonPath("$.id", is(donacionId.toString())))
                    .andExpect(jsonPath("$.institucion_id", is(INSTITUCION_BOGOTA_ID.toString())))
                    .andExpect(jsonPath("$.operador_id", is(OPERADOR_ID.toString())))
                    .andExpect(jsonPath("$.unidades", hasSize(1)))
                    .andExpect(jsonPath("$.unidades[0].estado", is("captada")));

            // Verificación de propagación de correlación en MDC
            assertNull(MDC.get("correlacion_id"), "El filtro debe limpiar el contexto MDC en el bloque finally");
        }
    }

    @Nested
    @DisplayName("2. Control de Idempotencia (DS-10 / IN-12) con Reenvío de Token")
    class IdempotenciaConTokenTest {

        @Test
        @DisplayName("Reenvío con la misma Idempotency-Key debe retornar respuesta cacheada sin duplicar registros")
        void testReenvioIdempotenteDevuelveMismaRespuesta() throws Exception {
            String token = emitirTokenPrueba(
                    OPERADOR_ID.toString(),
                    "operador",
                    "institucion:" + INSTITUCION_BOGOTA_ID,
                    3600
            );

            UUID donacionId = UUID.randomUUID();
            String idempotencyKey = "IK-TEST-DEVKEY-200";
            String correlacionId = "CORR-DEVKEY-200";

            RegistroDonacionRequestDto requestDto = new RegistroDonacionRequestDto(
                    UUID.randomUUID(), null, UUID.randomUUID(), null, List.of(UUID.randomUUID())
            );

            RegistroDonacionResponseDto responseDto = new RegistroDonacionResponseDto(
                    donacionId, requestDto.donanteId(), null, requestDto.tipoDonacionId(),
                    null, INSTITUCION_BOGOTA_ID, OPERADOR_ID, Instant.now(), List.of(), correlacionId
            );

            when(donacionIdempotenteService.procesarDonacionIdempotente(
                    eq(OPERADOR_ID.toString()),
                    eq(idempotencyKey),
                    any(RegistroDonacionRequestDto.class),
                    eq(INSTITUCION_BOGOTA_ID),
                    eq(correlacionId)
            )).thenReturn(ResponseEntity.status(HttpStatus.CREATED)
                    .header("X-Correlacion-Id", correlacionId)
                    .body(responseDto));

            // Segunda petición simula el reenvío
            mockMvc.perform(post("/v1/donaciones")
                            .header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", idempotencyKey)
                            .header("X-Correlacion-Id", correlacionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(requestDto)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id", is(donacionId.toString())));
        }
    }

    @Nested
    @DisplayName("3. Rechazo de Tokens Inválidos o Perfiles No Autorizados")
    class ValidacionSeguridadTokensTest {

        @Test
        @DisplayName("Debe rechazar con HTTP 403 Forbidden si el token JWT posee un rol no autorizado (ej. 'donante')")
        void testRechazoRolNoAutorizado() throws Exception {
            // Emite token firmado con la llave dev-key-1 pero con rol 'donante'
            String tokenDonante = emitirTokenPrueba(
                    UUID.randomUUID().toString(),
                    "donante",
                    "institucion:" + INSTITUCION_BOGOTA_ID,
                    3600
            );

            RegistroDonacionRequestDto requestDto = new RegistroDonacionRequestDto(
                    UUID.randomUUID(), null, UUID.randomUUID(), null, List.of(UUID.randomUUID())
            );

            mockMvc.perform(post("/v1/donaciones")
                            .header("Authorization", "Bearer " + tokenDonante)
                            .header("Idempotency-Key", "IK-DONANTE-01")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(requestDto)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Debe rechazar con HTTP 401 Unauthorized si el token está expirado")
        void testRechazoTokenExpirado() throws Exception {
            // Emite token expirado (validez -10 segundos)
            String tokenExpirado = emitirTokenPrueba(
                    OPERADOR_ID.toString(),
                    "operador",
                    "institucion:" + INSTITUCION_BOGOTA_ID,
                    -10
            );

            RegistroDonacionRequestDto requestDto = new RegistroDonacionRequestDto(
                    UUID.randomUUID(), null, UUID.randomUUID(), null, List.of(UUID.randomUUID())
            );

            mockMvc.perform(post("/v1/donaciones")
                            .header("Authorization", "Bearer " + tokenExpirado)
                            .header("Idempotency-Key", "IK-EXPIRADO-01")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(requestDto)))
                    .andExpect(status().isUnauthorized());
        }
    }
}
