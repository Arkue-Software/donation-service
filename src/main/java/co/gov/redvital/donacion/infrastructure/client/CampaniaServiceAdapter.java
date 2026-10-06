package co.gov.redvital.donacion.infrastructure.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * =============================================================================
 * REDVITAL - Plataforma Nacional de Gestión de Bancos de Sangre
 * Servicio de Donación (redvital-donacion-service - Java 25 / Spring Boot 4.1)
 * =============================================================================
 *
 * ADAPTADOR HACIA EL SERVICIO DE CAMPAÑAS (EC-08 / RF-03 / campanas-openapi)
 *
 * Cliente adaptador para la verificación sincrónica de campañas asociadas a una donación:
 *
 * 1. Plazo Máximo de Espera de 3 Segundos (EC-08 / Timeout Estricto):
 *    Configura un tiempo de espera no negociable de 3,000 ms. Si el Servicio de
 *    Campañas no responde dentro de este límite, se interrumpe la llamada y se
 *    activa la degradación elegante.
 *
 * 2. Resultado Tipado (ResultadoVerificacionCampania):
 *    Devuelve una estructura fuertemente tipada que encapsula la decisión operativa:
 *    - CONFIRMADA: Campaña verificada y en estado publicada.
 *    - NO_ENCONTRADA: La campaña no existe o no es visible (404).
 *    - ESTADO_NO_PERMITIDO: Campaña en borrador, cerrada o cancelada.
 *    - TIMEOUT_DEGRADADA: Se excedió el plazo de 3 segundos (EC-08).
 *    - DESCONECTADA_DEGRADADA: Fallo de red, 5xx o indisponibilidad del servicio.
 *
 * 3. Declaración Explícita de Degradación (EC-08):
 *    Ante cualquier error de conexión, timeout o indisponibilidad, el adaptador
 *    retorna un resultado degradado con campaniaId = NULL.
 *    REGLA INVIOLABLE EC-08: En ningún caso se detiene el registro de la donación
 *    ni se asigna una campaña por defecto. La interfaz declara la campaña como
 *    "no disponible".
 */
@Component
public class CampaniaServiceAdapter {

    private static final Logger log = LoggerFactory.getLogger(CampaniaServiceAdapter.class);

    private static final long TIMEOUT_SEGUNDOS = 3L;
    private static final String HEADER_CORRELACION = "X-Correlacion-Id";

    private final RestClient restClient;

    public CampaniaServiceAdapter(
            RestClient.Builder restClientBuilder,
            @Value("${redvital.campanias-service.url:http://redvital-campana-service:8083}") String campaniasBaseUrl
    ) {
        // Configuración de RestClient con timeouts de conexión y lectura
        this.restClient = restClientBuilder
                .baseUrl(campaniasBaseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // =========================================================================
    // ESTRUCTURAS TIPADAS DE RESPUESTA Y ESTADO
    // =========================================================================

    /**
     * Enumeración de estados de la verificación de campaña.
     */
    public enum EstadoVerificacion {
        SIN_CAMPAÑA,
        CONFIRMADA,
        NO_ENCONTRADA,
        ESTADO_NO_PERMITIDO,
        TIMEOUT_DEGRADADA,
        DESCONECTADA_DEGRADADA
    }

    /**
     * DTO que representa la respuesta deserializada del endpoint GET /v1/campanias/{id}.
     */
    public record CampaniaResponseDto(
            UUID id,
            UUID institucion_id,
            String territorio_codigo,
            String territorio_ruta,
            String nombre,
            String descripcion,
            String sede,
            String inicia_en,
            String termina_en,
            Integer cupo_total,
            Integer cupo_disponible,
            String estado,
            String publicada_en
    ) {}

    /**
     * Resultado Tipado de la Verificación de Campaña (EC-08).
     *
     * @param campaniaIdEfectivo Identificador de campaña para el registro en donación (NULL si fue degradada o no provista).
     * @param estadoVerificacion Clasificación tipada del resultado.
     * @param esDegradado Indica si se aplicó el protocolo de degradación EC-08 ante fallos o timeout.
     * @param mensajeDetalle Descripción operacional neutra.
     * @param campaniaDatos Datos de la campaña si fue confirmada exitosamente (opcional).
     */
    public record ResultadoVerificacionCampania(
            UUID campaniaIdEfectivo,
            EstadoVerificacion estadoVerificacion,
            boolean esDegradado,
            String mensajeDetalle,
            CampaniaResponseDto campaniaDatos
    ) {
        public static ResultadoVerificacionCampania sinCampania() {
            return new ResultadoVerificacionCampania(null, EstadoVerificacion.SIN_CAMPAÑA, false, "No se asoció campaña a la donación.", null);
        }

        public static ResultadoVerificacionCampania confirmada(CampaniaResponseDto dto) {
            return new ResultadoVerificacionCampania(dto.id(), EstadoVerificacion.CONFIRMADA, false, "Campaña verificada y confirmada exitosamente.", dto);
        }

        public static ResultadoVerificacionCampania noEncontrada(UUID campaniaIdBuscada) {
            return new ResultadoVerificacionCampania(null, EstadoVerificacion.NO_ENCONTRADA, false,
                    String.format("La campaña %s no existe en el Servicio de Campañas o no está disponible.", campaniaIdBuscada), null);
        }

        public static ResultadoVerificacionCampania estadoNoPermitido(UUID campaniaIdBuscada, String estadoActual) {
            return new ResultadoVerificacionCampania(null, EstadoVerificacion.ESTADO_NO_PERMITIDO, false,
                    String.format("La campaña %s se encuentra en estado '%s' y no permite registro de donaciones.", campaniaIdBuscada, estadoActual), null);
        }

        public static ResultadoVerificacionCampania degradadoTimeout(UUID campaniaIdBuscada) {
            return new ResultadoVerificacionCampania(null, EstadoVerificacion.TIMEOUT_DEGRADADA, true,
                    String.format("EC-08: Se superó el límite de tiempo de %d segundos al consultar la campaña %s. Donación registrada con campania_id nulo.", TIMEOUT_SEGUNDOS, campaniaIdBuscada), null);
        }

        public static ResultadoVerificacionCampania degradadoDesconectado(UUID campaniaIdBuscada, String motivoFallo) {
            return new ResultadoVerificacionCampania(null, EstadoVerificacion.DESCONECTADA_DEGRADADA, true,
                    String.format("EC-08: Indisponibilidad del Servicio de Campañas (%s) al consultar %s. Donación registrada con campania_id nulo.", motivoFallo, campaniaIdBuscada), null);
        }
    }

    // =========================================================================
    // MÉTODO PRINCIPAL DE VERIFICACIÓN CON TIMEOUT Y DEGRADACIÓN
    // =========================================================================

    /**
     * Consulta sincrónica con timeout de 3 segundos y degradación explícita (EC-08).
     *
     * Consume el endpoint GET /v1/campanias/{id} definido en campanas-openapi.
     *
     * @param campaniaIdBuscada Identificador UUID de la campaña indicada en la donación (puede ser null).
     * @param tokenJwt Token Bearer del operador de banco.
     * @param correlacionId Identificador de correlación para trazabilidad distribuida (X-Correlacion-Id).
     * @return ResultadoVerificacionCampania Estructura tipada con el resultado y la resolución de campaniaId.
     */
    public ResultadoVerificacionCampania verificarCampaniaConTimeout(
            UUID campaniaIdBuscada,
            String tokenJwt,
            String correlacionId
    ) {
        Instant inicio = Instant.now();

        // 1. Caso base: No se proporcionó ID de campaña
        if (campaniaIdBuscada == null) {
            return ResultadoVerificacionCampania.sinCampania();
        }

        log.info("Iniciando consulta sincrónica a Servicio de Campañas (GET /v1/campanias/{}). Timeout: {}s, Correlación: {}",
                campaniaIdBuscada, TIMEOUT_SEGUNDOS, correlacionId);

        try {
            // 2. Ejecución asíncrona acotada por CompletableFuture con Timeout estricto de 3 segundos (EC-08)
            CompletableFuture<ResultadoVerificacionCampania> peticionFuture = CompletableFuture.supplyAsync(() ->
                    ejecutarLlamadaHttp(campaniaIdBuscada, tokenJwt, correlacionId)
            );

            // Fuerza el timeout de 3 segundos sobre el futuro
            return peticionFuture.get(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS);

        } catch (TimeoutException e) {
            log.warn("EC-08 DEGRADACIÓN ACTIVADA: Timeout de {}s superado al consultar campaña {}. Correlación: {}",
                    TIMEOUT_SEGUNDOS, campaniaIdBuscada, correlacionId);

            // DEGRADACIÓN EC-08: Retorna resultado con campaniaId = NULL sin bloquear la donación
            return ResultadoVerificacionCampania.degradadoTimeout(campaniaIdBuscada);

        } catch (ExecutionException e) {
            Throwable causa = e.getCause();

            if (causa instanceof HttpClientErrorException.NotFound) {
                log.warn("Campaña {} no encontrada (404) en Servicio de Campañas. Correlación: {}", campaniaIdBuscada, correlacionId);
                return ResultadoVerificacionCampania.noEncontrada(campaniaIdBuscada);
            }

            if (causa instanceof HttpClientErrorException eClient) {
                log.warn("Error cliente HTTP ({}) al consultar campaña {}. Correlación: {}",
                        eClient.getStatusCode(), campaniaIdBuscada, correlacionId);
                return ResultadoVerificacionCampania.degradadoDesconectado(campaniaIdBuscada, "HTTP " + eClient.getStatusCode());
            }

            if (causa instanceof HttpServerErrorException eServer) {
                log.warn("EC-08 DEGRADACIÓN ACTIVADA: Error 5xx ({}) en Servicio de Campañas. Correlación: {}",
                        eServer.getStatusCode(), correlacionId);
                return ResultadoVerificacionCampania.degradadoDesconectado(campaniaIdBuscada, "HTTP 5xx Server Error");
            }

            log.warn("EC-08 DEGRADACIÓN ACTIVADA: Fallo de comunicación/red con Servicio de Campañas. Error: {}. Correlación: {}",
                    causa.getMessage(), correlacionId);
            return ResultadoVerificacionCampania.degradadoDesconectado(campaniaIdBuscada, causa.getMessage());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupción del hilo durante consulta de campaña {}. Activando degradación EC-08.", campaniaIdBuscada);
            return ResultadoVerificacionCampania.degradadoDesconectado(campaniaIdBuscada, "Interrupción de hilo");

        } catch (Exception e) {
            log.error("EC-08 DEGRADACIÓN ACTIVADA: Excepción inesperada al verificar campaña {}: {}. Correlación: {}",
                    campaniaIdBuscada, e.getMessage(), correlacionId, e);
            return ResultadoVerificacionCampania.degradadoDesconectado(campaniaIdBuscada, "Excepción inesperada");
        }
    }

    /**
     * Realiza la llamada HTTP GET /v1/campanias/{id} hacia el Servicio de Campañas.
     */
    private ResultadoVerificacionCampania ejecutarLlamadaHttp(UUID campaniaId, String tokenJwt, String correlacionId) {
        String authHeader = (tokenJwt != null && tokenJwt.startsWith("Bearer ")) ? tokenJwt : "Bearer " + tokenJwt;

        CampaniaResponseDto responseBody = restClient.get()
                .uri("/v1/campanias/{id}", campaniaId)
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .header(HEADER_CORRELACION, correlacionId)
                .retrieve()
                .body(CampaniaResponseDto.class);

        if (responseBody == null) {
            return ResultadoVerificacionCampania.degradadoDesconectado(campaniaId, "Cuerpo de respuesta nulo");
        }

        // Validación de estado de campaña: Para donaciones solo se aceptan campañas 'publicada'
        if (!"publicada".equalsIgnoreCase(responseBody.estado())) {
            log.warn("La campaña {} fue encontrada pero se encuentra en estado '{}' (requiere 'publicada'). Correlación: {}",
                    campaniaId, responseBody.estado(), correlacionId);
            return ResultadoVerificacionCampania.estadoNoPermitido(campaniaId, responseBody.estado());
        }

        return ResultadoVerificacionCampania.confirmada(responseBody);
    }
}
