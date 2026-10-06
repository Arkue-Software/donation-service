package co.gov.redvital.donacion.infrastructure.logging;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.TaskDecorator;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * =============================================================================
 * REDVITAL - Plataforma Nacional de Gestión de Bancos de Sangre
 * Servicio de Donación (redvital-donacion-service - Java 25 / Spring Boot 4.1)
 * =============================================================================
 *
 * PROPAGACIÓN Y REGISTRO DEL IDENTIFICADOR DE CORRELACIÓN EN LOGS DE APLICACIÓN
 *
 * Cumple de forma estricta con las reglas de trazabilidad distribuida y calidad:
 * - EC-20 / SAD V2.0 / DD Sec. 16: Trazabilidad distribuida mediante 'X-Correlacion-Id'.
 *   Permite correlacionar peticiones desde la puerta de enlace (Caddy / API Gateway)
 *   a través de la cadena de microservicios, eventos y registros de log.
 * - MDC (Mapped Diagnostic Context): Inyecta el 'correlacion_id' en el contexto de SLF4J
 *   garantizando que el 100% de los mensajes de log emitidos por controladores, servicios,
 *   repositorios y manejadores de excepción incluyan el ID de correlación automáticamente.
 * - Inyección y Generación Segura: Lee la cabecera 'X-Correlacion-Id'. Si el cliente no la
 *   proporciona, genera un identificador único estandarizado ('CORR-<uuid>').
 * - Devolución en Cabecera de Respuesta: Asigna 'X-Correlacion-Id' en la respuesta HTTP.
 * - Limpieza Obligatoria: Ejecuta MDC.clear() en el bloque finally para evitar fuga de
 *   contexto entre hilos del pool de Servlets (Tomcat/Netty).
 * - Propagación Outbound: Interceptor para RestTemplate/WebClient que inyecta 'X-Correlacion-Id'
 *   en llamadas hacia servicios externos (como el Servicio de Campañas).
 * - Propagación Asíncrona: MdcTaskDecorator para mantener el contexto MDC en hilos @Async.
 */
public class CorrelacionLoggingFilterMDC {

    public static final String HEADER_CORRELACION_ID = "X-Correlacion-Id";
    public static final String MDC_CORRELACION_KEY = "correlacion_id";
    public static final String PREFIX_CORRELACION = "CORR-";

    // =========================================================================
    // 1. FILTRO SERVLET DE MÁXIMA PRIORIDAD (MDC Context Filter)
    // =========================================================================

    /**
     * Filtro HTTP de máxima prioridad (@Order HIGHEST_PRECEDENCE) que intercepta
     * cada petición entrante antes de cualquier filtro de seguridad o Spring MVC.
     */
    @Component
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static class CorrelacionMdcFilter implements Filter {

        private static final Logger log = LoggerFactory.getLogger(CorrelacionMdcFilter.class);

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {

            if (request instanceof HttpServletRequest httpRequest && response instanceof HttpServletResponse httpResponse) {
                String correlacionId = extraerOGenerarCorrelacionId(httpRequest);

                // 1. Inyectar en SLF4J MDC para logs estructurados
                MDC.put(MDC_CORRELACION_KEY, correlacionId);

                // 2. Retornar en la cabecera HTTP de respuesta
                httpResponse.setHeader(HEADER_CORRELACION_ID, correlacionId);

                log.trace("Inicio de procesamiento de petición HTTP [{}] {}. Correlación: {}",
                        httpRequest.getMethod(), httpRequest.getRequestURI(), correlacionId);

                try {
                    chain.doFilter(request, response);
                } finally {
                    log.trace("Fin de procesamiento de petición HTTP [{}] {}. Estado: {}",
                            httpRequest.getMethod(), httpRequest.getRequestURI(), httpResponse.getStatus());

                    // 3. LIMPIEZA OBLIGATORIA DEL HILO (Evita fuga de memoria/contexto entre peticiones)
                    MDC.remove(MDC_CORRELACION_KEY);
                }
            } else {
                chain.doFilter(request, response);
            }
        }

        private String extraerOGenerarCorrelacionId(HttpServletRequest request) {
            String headerValue = request.getHeader(HEADER_CORRELACION_ID);
            if (headerValue != null && !headerValue.isBlank()) {
                return headerValue.trim();
            }
            return PREFIX_CORRELACION + UUID.randomUUID();
        }
    }

    // =========================================================================
    // 2. INTERCEPTOR CLIENTE HTTP OUTBOUND (Propagación hacia Campañas, etc.)
    // =========================================================================

    /**
     * Interceptor para RestTemplate / ClientHttpRequestInterceptor.
     * Garantiza que cualquier llamada realizada desde el Servicio de Donación hacia
     * otro microservicio (ej. Servicio de Campañas) propage la cabecera X-Correlacion-Id.
     */
    @Component
    public static class CorrelacionRestTemplateInterceptor implements ClientHttpRequestInterceptor {

        private static final Logger log = LoggerFactory.getLogger(CorrelacionRestTemplateInterceptor.class);

        @Override
        public ClientHttpResponse intercept(
                HttpRequest request,
                byte[] body,
                ClientHttpRequestExecution execution
        ) throws IOException {
            String correlacionId = MDC.get(MDC_CORRELACION_KEY);

            if (correlacionId != null && !correlacionId.isBlank()) {
                request.getHeaders().add(HEADER_CORRELACION_ID, correlacionId);
                log.debug("Propagando cabecera '{}': {} en llamada externa a {}",
                        HEADER_CORRELACION_ID, correlacionId, request.getURI());
            } else {
                String nuevoId = PREFIX_CORRELACION + UUID.randomUUID();
                request.getHeaders().add(HEADER_CORRELACION_ID, nuevoId);
                log.debug("Generando nueva cabecera '{}': {} en llamada externa a {}",
                        HEADER_CORRELACION_ID, nuevoId, request.getURI());
            }

            return execution.execute(request, body);
        }
    }

    // =========================================================================
    // 3. DECORADOR DE TAREAS ASÍNCRONAS (MDC TaskDecorator para Threads)
    // =========================================================================

    /**
     * Decorador de tareas para ExecutorService / @Async de Spring.
     * Transfiere el contexto MDC (incluyendo correlacion_id) del hilo padre
     * al hilo secundario de ejecución asíncrona.
     */
    public static class MdcTaskDecorator implements TaskDecorator {

        @Override
        public Runnable decorate(Runnable runnable) {
            Map<String, String> contextMap = MDC.getCopyOfContextMap();
            return () -> {
                try {
                    if (contextMap != null) {
                        MDC.setContextMap(contextMap);
                    }
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        }
    }
}
