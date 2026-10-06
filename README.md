# RedVital Blood Donation Management Service (`redvital-donacion-service`)

El microservicio **`redvital-donacion-service`** es el componente central de la Plataforma Nacional de Gestión de Bancos de Sangre RedVital en Colombia. Implementado en Java 25 y Spring Boot 3.4+, orquesta de manera atómica el ciclo de vida completo de las unidades de sangre: desde la captación del donante y el procesamiento en laboratorio, hasta el inventario por jurisdicción, la distribución hospitalaria y la disposición final o vencimiento programado.

---

## 1. Módulos y Arquitectura del Sistema

El sistema está diseñado bajo una arquitectura limpia y orientada al dominio (DDD), desacoplando los puertos de entrada HTTP, las reglas de negocio en el motor de estados y la persistencia en PostgreSQL 16.

### Módulos Principales de Dominio

* **Motor Único de Transición de Estados (`MotorTransicionEstadoUnidad`)**:
  Gobierna la máquina de estados finita cerrada compuesta por 9 estados (`captada`, `fraccionada`, `en_tamizaje`, `disponible`, `reservada`, `despachada`, `no_apta`, `vencida`, `desechada`) y valida de forma estricta las 13 transiciones permitidas en la base de datos.
* **Búsqueda Anónima de Donantes (`BusquedaDonanteConjuntoSintetico`)**:
  Ejecuta búsquedas de elegibilidad mediante resúmenes criptográficos HMAC-SHA256 sobre el documento de identidad, garantizando que los datos personales sensibles no se almacenen ni se expongan en los registros de la aplicación.
* **Captación e Idempotencia Transaccional (`RegistroDonacionIdempotenteDS10` y `RegistroDonacionConUnidadYEvento`)**:
  Permite el registro atómico de donaciones y la generación automática de unidades hemáticas asociadas en estado `captada`. Garantiza idempotencia de operaciones mediante la cabecera `Idempotency-Key` y huellas de petición almacenadas en la tabla `operacion_idempotente`.
* **Procesamiento Analítico y Tamizaje (`UnidadIngresoTamizajeController` y `UnidadTamizajeVeredictoController`)**:
  Soporta el flujo de laboratorio. Clasifica las unidades como `disponible` o `no_apta`. Garantiza la barrera de confidencialidad clínica: los motivos diagnósticos jamás se transmiten ni se persisten en las trazas operativas.
* **Consulta Indistinguible de Inventario (`RespuestaIndistinguibleInstitucionAjenaDS04`)**:
  Proporciona visibilidad del inventario con `cuenta_disponible = true`. Ante solicitudes fuera de la jurisdicción institucional del token JWT, retorna una respuesta HTTP 200 OK idéntica a una consulta sin existencias (lista vacía `[]`), evitando la enumeración maliciosa de recursos y registrando el evento de auditoría como `denegado`.
* **Despacho, Disposición Final y Auditoría (`UnidadDespachoConConfirmacionIN21`, `OperacionDisposicionFinalConfirmacionEC19` y `RegistroAuditoriaDespachoDesechoService`)**:
  Gestiona la reserva, el despacho interinstitucional y la incineración o baja de unidades no aptas o vencidas.
* **Proceso Programado de Vencimiento (`VencimientoUnidadesScheduledJobDS06`)**:
  Tarea programada que se ejecuta cada 5 minutos (`0 */5 * * * *`). Utiliza un bloqueo consultivo PostgreSQL (`pg_try_advisory_xact_lock`) para evitar ejecuciones concurrentes en pods replicados de Kubernetes EKS y marca automáticamente como `vencida` cualquier unidad cuya fecha haya expirado.

---

## 2. Estructura del Repositorio

La organización del proyecto sigue la convención estándar de proyectos Maven / Spring Boot:

```text
redvital-donacion-service/
├── pom.xml
├── README.md
├── guia-despliegue-redvital.md
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── co/
│   │   │       └── gov/
│   │   │           └── redvital/
│   │   │               └── donacion/
│   │   │                   ├── domain/
│   │   │                   │   ├── exception/          # Excepciones del dominio (TransicionEstadoInvalida, etc.)
│   │   │                   │   ├── model/              # Entidades JPA (Unidad, EventoUnidad, Donacion) y Enums
│   │   │                   │   └── service/            # Motor de estados y servicios de negocio
│   │   │                   ├── infrastructure/
│   │   │                   │   ├── client/             # Adaptadores de clientes externos (CampaniaServiceAdapter)
│   │   │                   │   ├── logging/            # Filtros MDC (CorrelacionLoggingFilterMDC)
│   │   │                   │   ├── repository/         # Repositorios Spring Data JPA
│   │   │                   │   └── scheduler/          # Jobs programados (VencimientoUnidadesScheduledJobDS06)
│   │   │                   └── web/
│   │   │                       └── controller/         # Controladores REST
│   │   └── resources/
│   │       ├── application.yml                         # Configuración de entorno y Spring Boot
│   │       └── db/
│   │           └── migration/                          # Scripts SQL de Flyway
│   │               ├── V1__incremento_donacion_y_roles.sql
│   │               └── V2__catalogo_transicion_valida.sql
│   └── test/
│       └── java/
│           └── co/
│               └── gov/
│                   └── redvital/
│                       └── donacion/                   # Pruebas unitarias, WebMvcTest e integración
```

---

## 3. Requisitos Previos y Tecnologías

* **Java Development Kit (JDK)**: Versión 25 o superior.
* **Build Tool**: Apache Maven 3.9+.
* **Base de Datos**: PostgreSQL 16+.
* **Seguridad & Tokens**: Servidor de Recursos OAuth2 con soporte para JSON Web Tokens (JWT) e inspección de la reivindicación `jurisdiction`.

---

## 4. Configuración y Variables de Entorno

El microservicio se configura mediante el archivo `src/main/resources/application.yml` y parametriza los siguientes valores a través de variables de entorno:

| Variable de Entorno | Descripción | Valor por Defecto |
| :--- | :--- | :--- |
| `SERVER_PORT` | Puerto HTTP expuesto por la aplicación. | `8080` |
| `DB_HOST` | Nombre de host o IP del servidor PostgreSQL RDS. | `localhost` |
| `DB_PORT` | Puerto de conexión a PostgreSQL. | `5432` |
| `DB_NAME` | Nombre de la base de datos relacional. | `db_donacion` |
| `DB_SERVICIOS_USER` | Usuario PostgreSQL de tiempo de ejecución para el servicio. | `donacion_servicio` |
| `DB_SERVICIOS_PASSWORD` | Contraseña del usuario de tiempo de ejecución. | `SecretServicio123!` |
| `DB_PROPIETARIO_USER` | Usuario propietario DDL para la ejecución de migraciones Flyway. | `donacion_propietario` |
| `DB_PROPIETARIO_PASSWORD` | Contraseña del usuario propietario. | `SecretPropietario123!` |
| `JWT_ISSUER_URI` | URI del servidor de identidad OAuth2 / OIDC. | `https://auth.redvital.gov.co/realms/redvital` |
| `CAMPANIA_SERVICE_URL` | URL base del microservicio externo de campañas. | `http://campania-service:8081` |

---

## 5. Esquema de Base de Datos y Migraciones SQL

El mantenimiento del esquema relacional en PostgreSQL se realiza de forma automatizada con **Flyway**:

1. **`V1__incremento_donacion_y_roles.sql`**:
   * Crea las tablas base: `donacion`, `unidad`, `evento_unidad`, `registro_auditoria`, `operacion_idempotente`, `consentimiento` y `reconocimiento_donante`.
   * Establece los roles de PostgreSQL (`donacion_propietario` y `donacion_servicio`) y aplica la política de seguridad con permisos restringidos de lectura y escritura (`GRANT SELECT, INSERT, UPDATE`).
2. **`V2__catalogo_transicion_valida.sql`**:
   * Puebla el catálogo de 9 estados en `estado_unidad`.
   * Define las 13 parejas válidas de origen, destino y tipo de actor (`usuario` o `sistema`) en la tabla `transicion_valida`.

---

## 6. Compilación, Pruebas y Ejecución Local

### Compilación y Ejecución de Pruebas

Para compilar el proyecto y ejecutar la suite completa de pruebas unitarias e integración:

```bash
mvn clean test
```

### Generación del Paquete Ejecutable

Para empaquetar el microservicio omitiendo la ejecución de pruebas:

```bash
mvn clean package -DskipTests
```

### Ejecución Local del Microservicio

Para iniciar la aplicación con el perfil activo por defecto:

```bash
java -jar target/redvital-donacion-service-1.0.0-SNAPSHOT.jar
```

---

## 7. Batería de Pruebas Automatizadas

El proyecto incluye las siguientes suites de prueba:

* **`MotorTransicionEstadoUnidadTest`**: Evalúa aisladamente la matriz de transiciones del motor de estados, verificando casos exitosos y bloqueos por transiciones no autorizadas (`TransicionEstadoInvalidaException`).
* **`RegistroDonacionIdempotenteDS10Test`**: Confirma la detección de peticiones duplicadas y la validación de coincidencia de huella de carga útil (`Idempotency-Key`).
* **`UnidadTamizajeVeredictoControllerTest`**: Valida los endpoints de laboratorio y la no divulgación de motivos de rechazo clínico.
* **`ExistenciasIndistinguibleControllerTest`**: Verifica el control de acceso por jurisdicción y el registro de auditoría `denegado`.
* **`UnidadDespachoYDisposicionFinalAuditoriaTest`**: Asegura la confirmación explícita antes de efectuar despachos o descartes de unidades.
* **`VencimientoUnidadesSembradasTest`**: Prueba el comportamiento del scheduler de vencimiento y la resiliencia en procesamientos por lotes.
* **`ServicioDonacionTokensLlavePruebaTest`**: Prueba de seguridad WebMvc para la autenticación JWT y extracción de autoridades.

---

## 8. Despliegue y Monitoreo

### Contenerización en Docker

El repositorio incluye soporte para construcción multi-etapa basada en imágenes Eclipse Temurin JDK 25 para la fase de compilación y JRE 25 sobre Alpine Linux para la ejecución del contenedor.

### Verificación de Estado (Actuator)

Una vez iniciado el microservicio, los endpoints de monitoreo están disponibles en:

* **Salud del Sistema**: `GET /actuator/health`
* **Métricas**: `GET /actuator/metrics`
* **Documentación Swagger / OpenAPI**: `GET /swagger-ui.html`
