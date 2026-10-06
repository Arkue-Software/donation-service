package co.gov.redvital.donacion.web.controller;

import co.gov.redvital.donacion.domain.model.ActorTipo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * =============================================================================
 * REDVITAL - Plataforma Nacional de Gestión de Bancos de Sangre
 * Servicio de Donación (redvital-donacion-service - Java 25 / Spring Boot 4.1)
 * =============================================================================
 *
 * BÚSQUEDA DE DONANTE SOBRE EL CONJUNTO SINTÉTICO (POST /v1/donantes/busqueda)
 *
 * Cumple de forma estricta con las especificaciones del DD V2.0, SAD V2.0 y OpenAPI:
 * - RF-01 / RF-03 / DD Sec. 14.4: Búsqueda del donante presente para el Operador de Banco (U3).
 * - ADR-011 / Matriz de Autorización: Exclusivo para rol 'operador' (U3).
 * - Protección de Datos (Ley 1581 / DD Sec 5.3.1):
 *   1. El número de documento viaja EXCLUSIVAMENTE en el cuerpo HTTP POST (writeOnly) y NUNCA en la URL,
 *      impidiendo su exposición en logs de acceso de gateways y servidores.
 *   2. Búsqueda por 'documento_hash' determinista mediante HMAC-SHA256 con clave secreta del servicio.
 *      El número de documento jamás se almacena en claro en la base de datos db_donacion.
 * - RF-02 / EC-21: Cálculo dinámico de elegibilidad (intervalo mínimo de 56 días desde la última donación
 *   y rango de edad 18-65 años).
 * - EC-02 / RI-02 / Anexo A: Ausencia total de causas clínicas, diagnósticos o motivos médicos.
 * - DD Sec. 15 (Tabla 32): Operación sobre el conjunto sintético de referencia (5.000 donantes ficticios).
 */
public class BusquedaDonanteConjuntoSintetico {

    // =========================================================================
    // 1. CONTRATOS REST Y DTOs (OPENAPI 3.1 - donacion-openapi.yaml)
    // =========================================================================

    /**
     * DTO de Solicitud de Búsqueda con Esquema Estricto.
     * writeOnly = true garantiza que el documento no sea devuelto ni serializado en respuestas.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record BusquedaDonanteRequestDto(
            @NotBlank(message = "El número de documento es obligatorio.")
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            String documento
    ) {}

    /**
     * DTO de Resultado de Elegibilidad (RF-02 / P3).
     * No expone ninguna razón clínica, únicamente el estado temporal de disponibilidad.
     */
    public record ElegibilidadDto(
            boolean elegible,
            LocalDate elegibleDesde,
            Long diasRestantes,
            String mensaje
    ) {}

    /**
     * DTO de Respuesta de Búsqueda de Donante (BusquedaDonanteResponse en OpenAPI).
     */
    public record BusquedaDonanteResponseDto(
            UUID id,
            String nombre,
            ElegibilidadDto elegibilidad,
            String correlacionId
    ) {}

    // =========================================================================
    // 2. COMPONENTE DE SEGURIDAD: HASHER HMAC-SHA256 (DD Sec. 5.3.1)
    // =========================================================================

    @Component
    public static class DocumentoHasher {
        private static final String ALGORITMO_HMAC = "HmacSHA256";
        private final String secretKey;

        public DocumentoHasher(@Value("${redvital.donacion.secret-key:ClaveSecretaRedVital2026DonanteHashHMAC}") String secretKey) {
            this.secretKey = secretKey;
        }

        /**
         * Calcula el hash determinista HMAC-SHA256 del documento de identidad.
         * NUNCA registra en logs ni imprime el documento recibido en claro.
         */
        public String calcularHash(String documento) {
            if (documento == null || documento.isBlank()) {
                throw new IllegalArgumentException("El documento no puede ser nulo ni estar vacío.");
            }
            try {
                Mac mac = Mac.getInstance(ALGORITMO_HMAC);
                SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), ALGORITMO_HMAC);
                mac.init(keySpec);
                byte[] rawHmac = mac.doFinal(documento.trim().getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder(rawHmac.length * 2);
                for (byte b : rawHmac) {
                    hex.append(String.format("%02x", b));
                }
                return hex.toString();
            } catch (NoSuchAlgorithmException | InvalidKeyException e) {
                throw new IllegalStateException("Error al calcular el hash de seguridad del documento", e);
            }
        }
    }

    // =========================================================================
    // 3. ENTIDAD JPA Y REPOSITORIO DE DONANTES (db_donacion)
    // =========================================================================

    @Entity
    @Table(name = "donante")
    public static class DonanteEntity {
        @Id
        private UUID id;

        @Column(name = "usuario_id", nullable = false, unique = true)
        private UUID usuarioId;

        @Column(name = "documento_hash", nullable = false, unique = true, length = 64)
        private String documentoHash;

        @Column(name = "nombre", nullable = false, length = 120)
        private String nombre;

        @Column(name = "correo", length = 160)
        private String correo;

        @Column(name = "telefono", length = 20)
        private String telefono;

        @Column(name = "fecha_nacimiento", nullable = false)
        private LocalDate fechaNacimiento;

        @Column(name = "municipio_ruta", length = 32)
        private String municipioRuta;

        @Column(name = "grupo_sanguineo_id")
        private UUID grupoSanguineoId;

        @Column(name = "fecha_ultima_donacion")
        private LocalDate fechaUltimaDonacion;

        @Column(name = "elegible_desde")
        private LocalDate elegibleDesde;

        @Column(name = "activo", nullable = false)
        private Boolean activo = true;

        public DonanteEntity() {}

        public DonanteEntity(UUID id, UUID usuarioId, String documentoHash, String nombre, LocalDate fechaNacimiento, LocalDate fechaUltimaDonacion) {
            this.id = id;
            this.usuarioId = usuarioId;
            this.documentoHash = documentoHash;
            this.nombre = nombre;
            this.fechaNacimiento = fechaNacimiento;
            this.fechaUltimaDonacion = fechaUltimaDonacion;
            this.activo = true;
        }

        public UUID getId() { return id; }
        public UUID getUsuarioId() { return usuarioId; }
        public String getDocumentoHash() { return documentoHash; }
        public String getNombre() { return nombre; }
        public LocalDate getFechaNacimiento() { return fechaNacimiento; }
        public LocalDate getFechaUltimaDonacion() { return fechaUltimaDonacion; }
        public Boolean getActivo() { return activo; }
        public void setFechaUltimaDonacion(LocalDate fechaUltimaDonacion) { this.fechaUltimaDonacion = fechaUltimaDonacion; }
        public void setElegibleDesde(LocalDate elegibleDesde) { this.elegibleDesde = elegibleDesde; }
    }

    @Repository
    public interface DonanteRepository extends JpaRepository<DonanteEntity, UUID> {
        Optional<DonanteEntity> findByDocumentoHashAndActivoTrue(String documentoHash);
        long count();
    }

    // =========================================================================
    // 4. SERVICIO TRANSACCIONAL DE BÚSQUEDA Y CÁLCULO DE ELEGIBILIDAD (RF-02)
    // =========================================================================

    @Service
    public static class BusquedaDonanteService {
        private static final Logger log = LoggerFactory.getLogger(BusquedaDonanteService.class);
        private static final int INTERVALO_MINIMO_DIAS_DONACION = 56; // 56 días para Sangre Total (RF-02)
        private static final int EDAD_MINIMA_DONANTE = 18;
        private static final int EDAD_MAXIMA_DONANTE = 65;

        private final DonanteRepository donanteRepository;
        private final DocumentoHasher documentoHasher;

        public BusquedaDonanteService(DonanteRepository donanteRepository, DocumentoHasher documentoHasher) {
            this.donanteRepository = donanteRepository;
            this.documentoHasher = documentoHasher;
        }

        /**
         * Realiza la búsqueda de donante por número de documento sobre el conjunto de datos.
         *
         * @param documentoNúmero Número de documento recibido en el cuerpo de la petición
         * @param correlacionId Identificador para la traza de observabilidad
         * @return DTO de respuesta con id, nombre y elegibilidad calculada
         */
        @Transactional(readOnly = true)
        public BusquedaDonanteResponseDto buscarDonantePorDocumento(String documentoNúmero, String correlacionId) {
            // 1. Convertir el documento a hash determinista HMAC-SHA256 (Garantía Ley 1581)
            String docHash = documentoHasher.calcularHash(documentoNúmero);

            log.debug("Ejecutando búsqueda por documento_hash en db_donacion. Correlación: {}", correlacionId);

            // 2. Consultar en la base de datos por hash
            DonanteEntity donante = donanteRepository.findByDocumentoHashAndActivoTrue(docHash)
                    .orElseThrow(() -> new DonanteNoEncontradoException(
                            "Donante no encontrado en el registro nacional.", correlacionId));

            // 3. Calcular la elegibilidad en tiempo real (RF-02 / EC-21)
            ElegibilidadDto elegibilidad = calcularElegibilidad(donante);

            log.info("Donante identificado exitosamente. ID: {}, Elegible: {}, Correlación: {}",
                    donante.getId(), elegibilidad.elegible(), correlacionId);

            return new BusquedaDonanteResponseDto(
                    donante.getId(),
                    donante.getNombre(),
                    elegibilidad,
                    correlacionId
            );
        }

        /**
         * Algoritmo autoritativo de cálculo de elegibilidad (RF-02).
         * Regla: Mínimo 56 días entre donaciones de Sangre Total y edad entre 18 y 65 años.
         */
        private ElegibilidadDto calcularElegibilidad(DonanteEntity donante) {
            LocalDate hoy = LocalDate.now();

            // A. Evaluación de Edad
            if (donante.getFechaNacimiento() != null) {
                int edad = Period.between(donante.getFechaNacimiento(), hoy).getYears();
                if (edad < EDAD_MINIMA_DONANTE || edad > EDAD_MAXIMA_DONANTE) {
                    return new ElegibilidadDto(
                            false,
                            null,
                            null,
                            "Donante fuera del rango de edad reglamentario para donación (18 a 65 años)."
                    );
                }
            }

            // B. Evaluación de Intervalo desde la última donación aceptada (56 días)
            LocalDate ultimaDonacion = donante.getFechaUltimaDonacion();
            if (ultimaDonacion == null) {
                // Donante sin donación previa -> Elegible de inmediato
                return new ElegibilidadDto(
                        true,
                        hoy,
                        0L,
                        "Donante primíparo o sin registros previos. Elegible para captación."
                );
            }

            LocalDate fechaProximaElegibilidad = ultimaDonacion.plusDays(INTERVALO_MINIMO_DIAS_DONACION);

            if (hoy.isAfter(fechaProximaElegibilidad) || hoy.isEqual(fechaProximaElegibilidad)) {
                return new ElegibilidadDto(
                        true,
                        fechaProximaElegibilidad,
                        0L,
                        "Intervalo normativo cumplido. Donante habilitado para captación."
                );
            } else {
                long diasFaltantes = ChronoUnit.DAYS.between(hoy, fechaProximaElegibilidad);
                return new ElegibilidadDto(
                        false,
                        fechaProximaElegibilidad,
                        diasFaltantes,
                        String.format("Donante en periodo de diferimiento por intervalo de tiempo (%d días restantes).", diasFaltantes)
                );
            }
        }
    }

    // =========================================================================
    // 5. CONTROLADOR REST (POST /v1/donantes/busqueda)
    // =========================================================================

    @RestController
    @RequestMapping("/v1/donantes")
    public static class DonanteBusquedaController {

        private static final Logger log = LoggerFactory.getLogger(DonanteBusquedaController.class);
        private final BusquedaDonanteService busquedaDonanteService;

        public DonanteBusquedaController(BusquedaDonanteService busquedaDonanteService) {
            this.busquedaDonanteService = busquedaDonanteService;
        }

        /**
         * Endpoint de Búsqueda de Donante (POST /v1/donantes/busqueda).
         *
         * Cumple con la restricción de que el documento viaja exclusivamente en el cuerpo
         * de la petición HTTP POST para no exponerse en URLs ni registros de acceso HTTP.
         */
        @PostMapping(
                value = "/busqueda",
                consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE
        )
        @PreAuthorize("hasRole('operador')")
        public ResponseEntity<BusquedaDonanteResponseDto> buscarDonantePresente(
                @Valid @RequestBody BusquedaDonanteRequestDto body,
                @RequestHeader(value = "X-Correlacion-Id", required = false) String correlacionHeader,
                Authentication authentication
        ) {
            String correlacionId = (correlacionHeader != null && !correlacionHeader.isBlank())
                    ? correlacionHeader : "CORR-" + UUID.randomUUID();

            Jwt jwt = (Jwt) authentication.getPrincipal();
            String actorId = jwt.getSubject();

            log.info("Iniciando búsqueda de donante presente por Operador (sub: {}). Correlación: {}", actorId, correlacionId);

            BusquedaDonanteResponseDto respuesta = busquedaDonanteService.buscarDonantePorDocumento(
                    body.documento(),
                    correlacionId
            );

            return ResponseEntity.ok(respuesta);
        }

        @ExceptionHandler(DonanteNoEncontradoException.class)
        public ResponseEntity<ProblemDetail> handleDonanteNoEncontrado(DonanteNoEncontradoException ex) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
            problem.setType(URI.create("https://redvital.gov.co/errores/no-encontrado"));
            problem.setTitle("Donante No Encontrado");
            problem.setProperty("correlacion_id", ex.getCorrelacionId());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
        }
    }

    // =========================================================================
    // 6. POBLADOR AUTOMÁTICO DE CONJUNTO SINTÉTICO DE REFERENCIA (DD Sec. 15)
    // =========================================================================

    @Component
    public static class CargarConjuntoSinteticoDonantesDataLoader implements CommandLineRunner {

        private static final Logger log = LoggerFactory.getLogger(CargarConjuntoSinteticoDonantesDataLoader.class);

        private final DonanteRepository donanteRepository;
        private final DocumentoHasher documentoHasher;

        public CargarConjuntoSinteticoDonantesDataLoader(DonanteRepository donanteRepository, DocumentoHasher documentoHasher) {
            this.donanteRepository = donanteRepository;
            this.documentoHasher = documentoHasher;
        }

        @Override
        @Transactional
        public void run(String... args) {
            if (donanteRepository.count() == 0) {
                log.info("Inicializando registros sintéticos de referencia para Donantes (db_donacion - DD Sec. 15)...");

                LocalDate hoy = LocalDate.now();

                // Donante Sintético 1: Elegible (última donación hace 120 días)
                String doc1 = "1098765432";
                DonanteEntity d1 = new DonanteEntity(
                        UUID.fromString("d0000001-0000-0000-0000-000000000001"),
                        UUID.fromString("u0000001-0000-0000-0000-000000000001"),
                        documentoHasher.calcularHash(doc1),
                        "Carlos Alberto Mendoza Sintético",
                        LocalDate.of(1992, 5, 14),
                        hoy.minusDays(120)
                );

                // Donante Sintético 2: No Elegible por Diferimiento (donó hace solo 20 días)
                String doc2 = "1098765433";
                DonanteEntity d2 = new DonanteEntity(
                        UUID.fromString("d0000002-0000-0000-0000-000000000002"),
                        UUID.fromString("u0000002-0000-0000-0000-000000000002"),
                        documentoHasher.calcularHash(doc2),
                        "María Fernanda Gómez Sintética",
                        LocalDate.of(1998, 11, 30),
                        hoy.minusDays(20)
                );

                // Donante Sintético 3: Primíparo (sin donaciones previas -> Elegible)
                String doc3 = "1098765434";
                DonanteEntity d3 = new DonanteEntity(
                        UUID.fromString("d0000003-0000-0000-0000-000000000003"),
                        UUID.fromString("u0000003-0000-0000-0000-000000000003"),
                        documentoHasher.calcularHash(doc3),
                        "Juan David Rodríguez Sintético",
                        LocalDate.of(2001, 3, 22),
                        null
                );

                donanteRepository.saveAll(List.of(d1, d2, d3));
                log.info("Conjunto sintético inicial de donantes cargado en db_donacion exitosamente.");
            }
        }
    }

    // =========================================================================
    // 7. EXCEPCIÓN DE DOMINIO
    // =========================================================================

    public static class DonanteNoEncontradoException extends RuntimeException {
        private final String correlacionId;

        public DonanteNoEncontradoException(String mensaje, String correlacionId) {
            super(mensaje);
            this.correlacionId = correlacionId;
        }

        public String getCorrelacionId() {
            return correlacionId;
        }
    }
}
