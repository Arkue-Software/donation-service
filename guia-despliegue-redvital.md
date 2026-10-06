# Guía de Despliegue Operativo e Infraestructura — Servicio de Donación RedVital

## 1. Visión General y Arquitectura del Microservicio

El microservicio `redvital-donacion-service` constituye el componente central del Módulo M3 (Trazabilidad y Ciclo de Vida de la Unidad) en la Plataforma Nacional de Gestión de Bancos de Sangre RedVital. Su responsabilidad principal abarca el registro de donaciones de sangre, la generación atómica de unidades hemáticas resultantes, la ejecución controlada de la máquina de estados sobre los componentes sanguíneos, la verificación de elegibilidad de donantes y la gestión del proceso automático de vencimiento.

```
                               ┌───────────────────────────────────┐
                               │       API Gateway / Caddy         │
                               └─────────────────┬─────────────────┘
                                                 │ X-Correlacion-Id
                                                 │ Bearer JWT
                                                 ▼
                               ┌───────────────────────────────────┐
                               │    redvital-donacion-service      │
                               │      (Java 25 / Spring 4.1)       │
                               └──────┬────────────────────┬───────┘
                                      │                    │
              Timeout 3s (EC-08)      │                    │  JDBC (TLS)
            Degradación Elegante      │                    │  UTC Timezone
                                      ▼                    ▼
       ┌─────────────────────────────────┐      ┌─────────────────────────────────┐
       │   redvital-campana-service      │      │           PostgreSQL 16         │
       │    (Servicio de Campañas)       │      │           (db_donacion)         │
       └─────────────────────────────────┘      └─────────────────────────────────┘
```

### Especificaciones Tecnológicas del Entorno

| Componente | Especificación Técnica | Función en la Arquitectura |
| :--- | :--- | :--- |
| **Lenguaje de Programación** | Java 25 | Entorno de ejecución de la aplicación con soporte para tipos `record` y concurrency. |
| **Framework Base** | Spring Boot 4.1.0 | Marco de trabajo para la capa REST, JPA/Hibernate, Security OAuth2 y Schedulers. |
| **Motor de Base de Datos** | PostgreSQL 16 | Almacenamiento relacional de la base de datos `db_donacion`. |
| **Mecanismo de Migración** | Flyway Migration | Evolución controlada de esquemas, catálogos y permisos (`V1` y `V2`). |
| **Seguridad de Capa HTTP** | OAuth2 Resource Server | Validación de firma JWT (RS256), extracción de `sub` (operador) y `jurisdiction`. |
| **Observabilidad / Logs** | SLF4J + MDC | Inyección del identificador `correlacion_id` a través de `CorrelacionLoggingFilterMDC`. |
| **Control Concurrente Batch** | PostgreSQL Advisory Lock | Bloqueo consultivo transaccional (`pg_try_advisory_xact_lock`) en jobs programados. |

---

## 2. Requisitos de Infraestructura y Variables de Entorno

El microservicio está diseñado para desplegarse como un contenedor inmutable en clústeres orquestados por Kubernetes (Amazon EKS). Todas las variables operativas se inyectan a través del entorno del contenedor.

### Matriz de Variables de Entorno

| Variable de Entorno | Valor por Defecto / Ejemplo | Descripción Operativa |
| :--- | :--- | :--- |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://db-donacion-rds.internal:5432/db_donacion?sslmode=verify-full` | Cadena JDBC de conexión a PostgreSQL con verificación estricta TLS/SSL. |
| `SPRING_DATASOURCE_USERNAME` | `donacion_servicio` | Usuario de tiempo de ejecución con permisos DML de lectura y escritura restringida. |
| `SPRING_DATASOURCE_PASSWORD` | `********` | Contraseña del usuario de tiempo de ejecución de la aplicación. |
| `SPRING_FLYWAY_URL` | `jdbc:postgresql://db-donacion-rds.internal:5432/db_donacion` | Cadena JDBC para ejecución de migraciones en arranque. |
| `SPRING_FLYWAY_USER` | `donacion_propietario` | Usuario DDL propietario de las tablas con privilegios administrativos de migración. |
| `SPRING_FLYWAY_PASSWORD` | `********` | Contraseña del usuario propietario de migraciones. |
| `REDVITAL_CAMPANIAS_SERVICE_URL` | `http://redvital-campana-service:8083` | URL base del microservicio interno de gestión de campañas de donación. |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | `https://identidad.redvital.local` | URI del emisor de tokens JWT para validación de firma y claims. |
| `REDVITAL_DONACION_SECRET_KEY` | `ClaveSecretaRedVital2026DonanteHashHMAC` | Clave secreta para el cálculo determinista HMAC-SHA256 del documento del donante. |
| `TZ` | `UTC` | Zona horaria del contenedor forzada a Tiempo Universal Coordinado. |

### Configuración de Recursos de Memoria JVM

Para garantizar un rendimiento predecible y evitar reinicios por OOM (Out Of Memory) en Kubernetes, la máquina virtual Java debe configurarse respetando los límites del contenedor:

* **Límite de Memoria del Pod**: 1024 MiB
* **Reserva de Memoria del Pod**: 512 MiB
* **Parámetros JVM Recomendados**: `-Xms512m -Xmx768m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError`

---

## 3. Estrategia de Base de Datos y Migraciones Flyway

La base de datos `db_donacion` opera bajo un modelo estricto de separación de responsabilidades a nivel de roles de PostgreSQL, garantizando el principio de menor privilegio y la inmutabilidad de los registros de auditoría y eventos.

### Modelo de Doble Rol en PostgreSQL

1. **Rol Propietario (`donacion_propietario`)**:
   * Posee la titularidad de todas las tablas y objetos del esquema `public`.
   * Es utilizado exclusivamente por Flyway en la fase de migración DDL.
2. **Rol de Servicio (`donacion_servicio`)**:
   * Utilizado por la aplicación en tiempo de ejecución.
   * No posee permisos de creación ni modificación de estructuras DDL (`REVOKE ALL ON ALL TABLES IN SCHEMA public FROM donacion_servicio`).
   * Permisos DML limitados por tabla según el modelo de dominio.

### Matriz de Permisos DML de la Aplicación (`donacion_servicio`)

```
                               ┌────────────────────────────────────────────────┐
                               │            Base de Datos db_donacion           │
                               └───────────────────────┬────────────────────────┘
                                                       │
               ┌───────────────────────────────────────┼───────────────────────────────────────┐
               │                                       │                                       │
               ▼                                       ▼                                       ▼
    Tablas Append-Only                     Entidades Parcialmente Mutables            Tabla Técnica Idempotencia
 (evento_unidad, registro_auditoria,        (unidad, intencion_donacion)               (operacion_idempotente)
  consentimiento, reconocimiento)                      │                                       │
               │                                       │                                       │
               ▼                                       ▼                                       ▼
        SELECT, INSERT                       SELECT, INSERT, UPDATE                      SELECT, INSERT,
    (UPDATE/DELETE Denegado)                   (Columnas específicas)                     UPDATE, DELETE
```

* **Tablas de Solo Anexado (Append-Only)**: `evento_unidad`, `registro_auditoria`, `consentimiento`, `reconocimiento_donante`.
  * Permisos: `GRANT SELECT, INSERT`. Los comandos `UPDATE` y `DELETE` están revocados a nivel del motor PostgreSQL para garantizar no repudio (ST2 / RNF-09).
* **Entidades Inmutables del Dominio**: `donacion`.
  * Permisos: `GRANT SELECT, INSERT`.
* **Entidades Parcialmente Mutables**:
  * `unidad`: `GRANT SELECT, INSERT` y `GRANT UPDATE (estado_id, apta, institucion_custodia_id, actualizado_en)`.
  * `intencion_donacion`: `GRANT SELECT, INSERT` y `GRANT UPDATE (estado, atendida_en)`.
* **Catálogos**: `tipo_donacion`, `estado_unidad`, `transicion_valida`.
  * Permisos: `GRANT SELECT`.
* **Tabla Técnica de Idempotencia**: `operacion_idempotente`.
  * Permisos: `GRANT SELECT, INSERT, UPDATE, DELETE` (permite depuración física de registros expirados tras 24 horas).

### Secuencia de Migraciones Flyway

Las migraciones se ejecutan automáticamente en el arranque del servicio a través de los siguientes scripts ubicados en `classpath:db/migration`:

1. **`V1__incremento_donacion_y_roles.sql`**:
   * Crea las tablas del núcleo de donación (`tipo_donacion`, `intencion_donacion`, `donacion`, `unidad`, `evento_unidad`, `registro_auditoria`, `consentimiento`, `reconocimiento_donante`, `operacion_idempotente`).
   * Configura los permisos granulares `GRANT` / `REVOKE` entre `donacion_propietario` y `donacion_servicio`.
2. **`V2__catalogo_transicion_valida.sql`**:
   * Puebla los 9 estados oficiales de la unidad (`captada`, `fraccionada`, `en_tamizaje`, `disponible`, `reservada`, `despachada`, `no_apta`, `vencida`, `desechada`).
   * Puebla la matriz cerrada de 13 transiciones válidas (`transicion_valida`).

---

## 4. Contenerización con Docker

El empaquetado del servicio se realiza mediante un `Dockerfile` multi-etapa para generar imágenes ligeras, seguras y sin herramientas de compilación en el artefacto final.

### Dockerfile Optimizado para Producción

```dockerfile
# =============================================================================
# ETAPA 1: Compilación y Empaquetado
# =============================================================================
FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /workspace/app

# Copiar archivos de proyecto Maven
COPY pom.xml .
COPY src src

# Compilar y empaquetar omitiendo pruebas unitarias en build de imagen
RUN ./mvnw clean package -DskipTests

# Extraer capas de Spring Boot Layered JAR
RUN java -Djarmode=layertools -jar target/redvital-donacion-service-1.0.0.jar extract

# =============================================================================
# ETAPA 2: Imagen de Ejecución Ligera (Runtime)
# =============================================================================
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Crear usuario no privilegiado para ejecución segura
RUN addgroup -S redvital && adduser -S redvitaluser -G redvital

# Copiar capas extraídas desde la etapa builder
COPY --from=builder /workspace/app/dependencies/ ./
COPY --from=builder /workspace/app/spring-boot-loader/ ./
COPY --from=builder /workspace/app/snapshot-dependencies/ ./
COPY --from=builder /workspace/app/application/ ./

# Asignar propiedad al usuario no privilegiado
RUN chown -R redvitaluser:redvital /app

USER redvitaluser:redvital

# Configuración de puerto y variables por defecto
EXPOSE 8082
ENV TZ=UTC
ENV JAVA_OPTS="-Xms512m -Xmx768m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

# Comprobación de salud básica de la imagen
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8082/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
```

---

## 5. Orquestación en Kubernetes (AWS EKS)

Para el despliegue en producción en AWS EKS, se definen los manifiestos declarativos que garantizan alta disponibilidad, aislamiento de configuración y seguridad de ejecución.

### Manifiesto de Despliegue (`deployment.yaml`)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redvital-donacion-service
  namespace: redvital-prod
  labels:
    app.kubernetes.io/name: redvital-donacion-service
    app.kubernetes.io/part-of: redvital-platform
spec:
  replicas: 2
  selector:
    matchLabels:
      app: redvital-donacion-service
  template:
    metadata:
      labels:
        app: redvital-donacion-service
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 10001
        runAsGroup: 10001
        fsGroup: 10001
      containers:
        - name: donacion-service
          image: 123456789012.dkr.ecr.us-east-1.amazonaws.com/redvital/donacion-service:1.0.0
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8082
              name: http
          envFrom:
            - configMapRef:
                name: redvital-donacion-config
            - secretRef:
                name: redvital-donacion-secrets
          resources:
            requests:
              memory: "512Mi"
              cpu: "250m"
            limits:
              memory: "1024Mi"
              cpu: "1000m"
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8082
            initialDelaySeconds: 40
            periodSeconds: 15
            timeoutSeconds: 3
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8082
            initialDelaySeconds: 20
            periodSeconds: 10
            timeoutSeconds: 3
            failureThreshold: 2
```

### Manifiesto de Configuración (`configmap.yaml`)

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: redvital-donacion-config
  namespace: redvital-prod
data:
  TZ: "UTC"
  SPRING_DATASOURCE_URL: "jdbc:postgresql://db-donacion-rds.redvital.internal:5432/db_donacion?sslmode=verify-full"
  SPRING_FLYWAY_URL: "jdbc:postgresql://db-donacion-rds.redvital.internal:5432/db_donacion"
  REDVITAL_CAMPANIAS_SERVICE_URL: "http://redvital-campana-service.redvital-prod.svc.cluster.local:8083"
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: "https://identidad.redvital.gov.co"
```

### Control Concurrente en Despliegues Multirréplica

Al desplegar múltiples réplicas (pods) del servicio en Kubernetes, el job programado `VencimientoUnidadesScheduledJobDS06` se ejecuta concurrentemente cada 5 minutos (`0 */5 * * * *`). Para evitar condiciones de carrera o bloqueos a nivel de fila durante la actualización masiva de unidades vencidas:

1. El job solicita un **bloqueo consultivo a nivel de transacción de PostgreSQL** utilizando la función `pg_try_advisory_xact_lock(0x56454E434155544FL)`.
2. La réplica que obtiene primero el bloqueo procesa el lote completo de unidades expiradas.
3. Las demás réplicas reciben un retorno `FALSE` inmediato y omiten la ejecución sin bloquear sus hilos ni generar errores en los logs (`Bloqueo consultivo DS-06 ocupado por otra instancia`).

---

## 6. Integración de Observabilidad y Seguridad

### Trazabilidad Distribuida y Logs Estructurados

Cada petición HTTP entrante es procesada por el filtro `CorrelacionLoggingFilterMDC`:

1. Extrae el identificador de la cabecera HTTP `X-Correlacion-Id`. Si no está presente, genera un UUID estandarizado con el prefijo `CORR-`.
2. Inyecta el valor en el mapa diagnostic de SLF4J MDC (`MDC.put("correlacion_id", correlacionId)`).
3. Asegura que cada línea de registro emitida en cualquier capa incluya la clave `correlacion_id`.
4. Asigna la cabecera `X-Correlacion-Id` en la respuesta HTTP.
5. Ejecuta `MDC.remove("correlacion_id")` en el bloque `finally` para prevenir la contaminación de contexto entre hilos del pool Servlet.
6. Propaga automáticamente el encabezado en llamadas HTTP salientes mediante `CorrelacionRestTemplateInterceptor`.

### Seguridad de Acceso y Contexto de Jurisdicción

El servicio implementa autenticación basada en tokens JWT firmados con RS256:

* **Validación de Roles**: Exige `@PreAuthorize("hasRole('operador')")` para operaciones de captura, tamizaje y despacho; y `@PreAuthorize("hasAnyRole('operador', 'admin_banco')")` para consultas de existencias.
* **Extracción de Jurisdicción Institutional**: La institución custodia del operador se extrae estrictamente de la reivindicación `jurisdiction` del JWT (formato `institucion:<uuid>`), impidiendo que un operador consulte o modifique datos de instituciones ajenas a su ámbito asignado.
* **Respuesta Indistinguible (`EC-01` / `RNI-03`)**: Si un usuario intenta consultar inventario de una institución fuera de su jurisdicción, la aplicación asienta un registro de auditoría con resultado `denegado` y retorna HTTP 200 OK con una lista vacía `[]`, impidiendo la enumeración de recursos o la confirmación de existencia de instituciones ajenas.

### Resiliencia y Degradación Elegante (`EC-08`)

En el registro de donación (`POST /v1/donaciones`), la asociación con una campaña activa consulta al Servicio de Campañas mediante `CampaniaServiceAdapter`:

* Se impone un **timeout estricto no negociable de 3,000 ms** (3 segundos).
* Si el Servicio de Campañas supera los 3 segundos, retorna error HTTP 5xx o no está accesible, se activa el protocolo de degradación `EC-08`.
* La donación se registra exitosamente asignando `campania_id = NULL` de forma transparente. En ningún caso se bloquea el registro de la donación ni se asigna una campaña por defecto.

---

## 7. Procedimiento de Despliegue y Verificación Post-Instalación

### Pasos de Despliegue Secuencial

1. **Verificación de Conectividad a PostgreSQL**:
   Confirmar resolución DNS y alcance de red desde el clúster hacia la instancia RDS PostgreSQL.
2. **Aplicación de Manifiestos de Kubernetes**:
   ```bash
   kubectl apply -f configmap.yaml -n redvital-prod
   kubectl apply -f secrets.yaml -n redvital-prod
   kubectl apply -f deployment.yaml -n redvital-prod
   kubectl apply -f service.yaml -n redvital-prod
   ```
3. **Monitoreo del Estado de Despliegue**:
   ```bash
   kubectl rollout status deployment/redvital-donacion-service -n redvital-prod
   ```

### Lista de Verificación Post-Despliegue

* [ ] **Ejecución de Migraciones Flyway**: Inspeccionar logs de arranque del Pod y verificar que Flyway aplicó exitosamente los scripts `V1` y `V2`.
  ```bash
  kubectl logs -l app=redvital-donacion-service -n redvital-prod | grep "Successfully applied SQL migration"
  ```
* [ ] **Verificación de Roles y Tabla de Estados**: Confirmar en base de datos la presencia de los 9 estados y las 13 transiciones válidas.
  ```sql
  SELECT COUNT(*) FROM estado_unidad; -- Debe retornar 9
  SELECT COUNT(*) FROM transicion_valida; -- Debe retornar 13
  ```
* [ ] **Sondas de Salud (Healthcheck)**: Verificar respuesta HTTP 200 OK en las rutas de actuator.
  ```bash
  curl -s http://redvital-donacion-service.redvital-prod.svc.cluster.local:8082/actuator/health
  ```
* [ ] **Prueba de Idempotencia (`DS-10`)**: Transmitir una solicitud `POST /v1/donaciones` con cabecera `Idempotency-Key` y reenviar la misma petición verificando que el segundo intento retorne la respuesta original reconstruida sin duplicar registros en la tabla `donacion` ni en `unidad`.
* [ ] **Prueba de Degradación de Campañas (`EC-08`)**: Simular indisponibilidad de `redvital-campana-service` y confirmar que el registro de donación responde en HTTP 201 Created con `campania_id = null` en un tiempo inferior a 3.5 segundos.

### Plan de Reversión (Rollback)

En caso de fallo crítico en la fase de verificación post-despliegue:

1. Ejecutar el rollback inmediato de la versión en Kubernetes:
   ```bash
   kubectl rollout undo deployment/redvital-donacion-service -n redvital-prod
   ```
2. Confirmar que la versión previa restablezca el tráfico sin afectar la integridad del esquema de base de datos (los scripts `V1` y `V2` son retrocompatibles).
