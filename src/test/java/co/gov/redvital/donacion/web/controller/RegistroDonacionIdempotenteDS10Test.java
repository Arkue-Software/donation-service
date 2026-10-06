package co.gov.redvital.donacion.web.controller;

import co.gov.redvital.donacion.web.controller.RegistroDonacionIdempotenteDS10.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Pruebas del Mecanismo de Idempotencia del Registro de Donación (DS-10 / EC-10 / IN-12)")
class RegistroDonacionIdempotenteDS10Test {

    @Mock private OperacionIdempotenteRepository idempotenciaRepository;

    @Captor private ArgumentCaptor<OperacionIdempotenteEntity> idempotenciaCaptor;

    private RegistroDonacionIdempotenteService service;

    private static final String SUJETO_OPERADOR_A = "11111111-1111-1111-1111-111111111111";
    private static final String SUJETO_OPERADOR_B = "22222222-2222-2222-2222-222222222222";
    private static final String CLAVE_IDEMPOTENCIA = "IDEM-DONACION-KEY-999";
    private static final UUID INSTITUCION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID TIPO_DONACION_ID = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11");

    @BeforeEach
    void setUp() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        service = new RegistroDonacionIdempotenteService(idempotenciaRepository, objectMapper);
    }

    @Test
    @DisplayName("Primera Petición: Crea la donación y guarda el registro técnico en operacion_idempotente")
    void testPrimeraPeticionCreaDonacionYRegistraIdempotencia() {
        when(idempotenciaRepository.findBySujetoAndClave(SUJETO_OPERADOR_A, CLAVE_IDEMPOTENCIA))
                .thenReturn(Optional.empty());

        RegistroDonacionRequestDto body = new RegistroDonacionRequestDto(
                UUID.randomUUID(), null, TIPO_DONACION_ID, null, List.of(UUID.randomUUID())
        );

        ResponseEntity<RegistroDonacionResponseDto> respuesta = service.procesarDonacionIdempotente(
                SUJETO_OPERADOR_A, CLAVE_IDEMPOTENCIA, body, INSTITUCION_ID, "CORR-001"
        );

        assertEquals(HttpStatus.CREATED, respuesta.getStatusCode());
        assertNotNull(respuesta.getBody());
        assertNotNull(respuesta.getBody().id());

        verify(idempotenciaRepository, times(1)).save(idempotenciaCaptor.capture());
        OperacionIdempotenteEntity guardado = idempotenciaCaptor.getValue();
        assertEquals(SUJETO_OPERADOR_A, guardado.getSujeto());
        assertEquals(CLAVE_IDEMPOTENCIA, guardado.getClave());
        assertEquals("REGISTRO_DONACION", guardado.getOperacion());
        assertEquals(201, guardado.getCodigoEstado());
        assertNotNull(guardado.getHuellaPeticion());
        assertEquals(respuesta.getBody().id(), guardado.getRecursoId());
    }

    @Test
    @DisplayName("Reenvío con Misma Clave y Misma Huella: Devuelve respuesta original idéntica sin duplicar")
    void testReenvioMismaClaveYMismaHuellaDevuelveRespuestaOriginal() {
        UUID recursoDonacionId = UUID.randomUUID();
        RegistroDonacionRequestDto body = new RegistroDonacionRequestDto(
                UUID.randomUUID(), null, TIPO_DONACION_ID, null, List.of(UUID.randomUUID())
        );

        // Precalcular huella
        String huellaEsperada;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(body).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            huellaEsperada = hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        OperacionIdempotenteEntity existente = new OperacionIdempotenteEntity();
        existente.setSujeto(SUJETO_OPERADOR_A);
        existente.setClave(CLAVE_IDEMPOTENCIA);
        existente.setOperacion("REGISTRO_DONACION");
        existente.setHuellaPeticion(huellaEsperada);
        existente.setRecursoId(recursoDonacionId);
        existente.setCodigoEstado(201);
        existente.setCreadoEn(Instant.now());
        existente.setExpiraEn(Instant.now().plusSeconds(86400));

        when(idempotenciaRepository.findBySujetoAndClave(SUJETO_OPERADOR_A, CLAVE_IDEMPOTENCIA))
                .thenReturn(Optional.of(existente));

        ResponseEntity<RegistroDonacionResponseDto> respuesta = service.procesarDonacionIdempotente(
                SUJETO_OPERADOR_A, CLAVE_IDEMPOTENCIA, body, INSTITUCION_ID, "CORR-002"
        );

        assertEquals(HttpStatus.CREATED, respuesta.getStatusCode());
        assertNotNull(respuesta.getBody());
        assertEquals(recursoDonacionId, respuesta.getBody().id());

        // NUNCA guarda un nuevo registro ni duplica entidades
        verify(idempotenciaRepository, never()).save(any());
    }

    @Test
    @DisplayName("Reutilización de Clave con Cuerpo Distinto: Lanza IdempotenciaPayloadMismatchException (422)")
    void testReutilizacionClaveCuerpoDistintoLanza422() {
        OperacionIdempotenteEntity existente = new OperacionIdempotenteEntity();
        existente.setSujeto(SUJETO_OPERADOR_A);
        existente.setClave(CLAVE_IDEMPOTENCIA);
        existente.setOperacion("REGISTRO_DONACION");
        existente.setHuellaPeticion("HUELLA_PETICION_ANTERIOR_ABC123");
        existente.setRecursoId(UUID.randomUUID());
        existente.setCodigoEstado(201);

        when(idempotenciaRepository.findBySujetoAndClave(SUJETO_OPERADOR_A, CLAVE_IDEMPOTENCIA))
                .thenReturn(Optional.of(existente));

        RegistroDonacionRequestDto bodyNuevo = new RegistroDonacionRequestDto(
                UUID.randomUUID(), null, TIPO_DONACION_ID, null, List.of(UUID.randomUUID(), UUID.randomUUID())
        );

        assertThrows(IdempotenciaPayloadMismatchException.class, () ->
                service.procesarDonacionIdempotente(SUJETO_OPERADOR_A, CLAVE_IDEMPOTENCIA, bodyNuevo, INSTITUCION_ID, "CORR-003")
        );

        verify(idempotenciaRepository, never()).save(any());
    }

    @Test
    @DisplayName("Aislamiento entre Sujetos: Misma clave usada por dos operadores distintos no colisiona")
    void testAislamientoEntreSujetosMismaClave() {
        when(idempotenciaRepository.findBySujetoAndClave(SUJETO_OPERADOR_B, CLAVE_IDEMPOTENCIA))
                .thenReturn(Optional.empty());

        RegistroDonacionRequestDto body = new RegistroDonacionRequestDto(
                UUID.randomUUID(), null, TIPO_DONACION_ID, null, List.of(UUID.randomUUID())
        );

        ResponseEntity<RegistroDonacionResponseDto> respuesta = service.procesarDonacionIdempotente(
                SUJETO_OPERADOR_B, CLAVE_IDEMPOTENCIA, body, INSTITUCION_ID, "CORR-004"
        );

        assertEquals(HttpStatus.CREATED, respuesta.getStatusCode());
        verify(idempotenciaRepository, times(1)).save(idempotenciaCaptor.capture());
        assertEquals(SUJETO_OPERADOR_B, idempotenciaCaptor.getValue().getSujeto());
    }
}
