-- =============================================================================
-- REDVITAL - Plataforma Nacional de Gestión de Bancos de Sangre
-- Servicio de Donación (db_donacion - PostgreSQL 16)
-- Script de Migración Flyway e Inicialización de Seguridad/Roles
-- Archivo: V1__incremento_donacion_y_roles.sql
-- =============================================================================

-- =============================================================================
-- PARTE 1: ESQUEMA DE BASE DE DATOS (INCREMENTO DE DONACIÓN - FLYWAY)
-- =============================================================================

-- 1. Catálogo: tipo_donacion (RNF-05, RI-01, EC-24, PD-04)
-- Modela el material donado como un valor de catálogo y no como una estructura rígida.
CREATE TABLE tipo_donacion (
    id UUID PRIMARY KEY,
    codigo VARCHAR(40) NOT NULL UNIQUE,
    nombre VARCHAR(80) NOT NULL,
    vigente BOOLEAN NOT NULL DEFAULT TRUE
);

-- Inserción del valor inicial obligatorio
INSERT INTO tipo_donacion (id, codigo, nombre, vigente) 
VALUES ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'sangre_total', 'Sangre Total', TRUE);


-- 2. Registro Anónimo: intencion_donacion (RF-01, EC-17, D-08)
-- Intención previa sin campos de identificación personal. Caduca a los 30 días.
CREATE TABLE intencion_donacion (
    id UUID PRIMARY KEY,
    codigo VARCHAR(12) NOT NULL UNIQUE,
    grupo_sanguineo_id UUID NULL,
    municipio_ruta VARCHAR(32) NULL,
    estado VARCHAR(20) NOT NULL CHECK (estado IN ('pendiente', 'atendida', 'caducada')),
    creado_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expira_en TIMESTAMPTZ NOT NULL,
    atendida_en TIMESTAMPTZ NULL
);


-- 3. Entidad Central: donacion (RF-03, IN-07, EC-08)
-- Registro del acto de donación en banco de sangre. donante_id e intencion_id son excluyentes.
CREATE TABLE donacion (
    id UUID PRIMARY KEY,
    donante_id UUID NULL,
    intencion_id UUID NULL UNIQUE REFERENCES intencion_donacion(id),
    tipo_donacion_id UUID NOT NULL REFERENCES tipo_donacion(id),
    institucion_id UUID NOT NULL, -- Referencia lógica a db_institucional (sin FK física)
    campania_id UUID NULL,       -- Referencia lógica a db_campana (Opcional por degradación EC-08)
    fecha_captacion TIMESTAMPTZ NOT NULL,
    operador_id UUID NOT NULL,   -- Referencia lógica a db_identidad (sub claim JWT)
    creado_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Restricción IN-07: donante_id e intencion_id nunca son no nulos simultáneamente
    CONSTRAINT chk_donante_intencion_excluyente CHECK (
        NOT (donante_id IS NOT NULL AND intencion_id IS NOT NULL)
    )
);


-- 4. Entidad Central: unidad (RF-03, RF-04, IN-03)
-- Ejemplar individual del componente sanguíneo.
CREATE TABLE unidad (
    id UUID PRIMARY KEY,
    donacion_id UUID NOT NULL REFERENCES donacion(id),
    componente_id UUID NOT NULL,
    grupo_sanguineo_id UUID NOT NULL,
    institucion_custodia_id UUID NOT NULL, -- Referencia lógica a db_institucional
    estado_id UUID NOT NULL,
    apta BOOLEAN NULL,                     -- Veredicto de tamizaje (NULL hasta tamizaje)
    fecha_vencimiento DATE NOT NULL,
    volumen_ml INTEGER NULL,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actualizado_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);


-- 5. Tabla de Solo Anexado: evento_unidad (RF-04, EC-04, EC-05, IN-06)
-- Historial inmutable de transiciones de estado de la unidad de sangre.
CREATE TABLE evento_unidad (
    id UUID PRIMARY KEY,
    unidad_id UUID NOT NULL REFERENCES unidad(id),
    estado_anterior_id UUID NULL,
    estado_nuevo_id UUID NOT NULL,
    actor_tipo VARCHAR(20) NOT NULL CHECK (actor_tipo IN ('usuario', 'sistema')),
    actor_id UUID NOT NULL,
    institucion_id UUID NOT NULL,
    observacion_id UUID NULL,
    correlacion_id VARCHAR(36) NOT NULL,
    ocurrido_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);


-- 6. Tabla de Solo Anexado: registro_auditoria (ST2, RNF-09, ADR-016, IN-06)
-- Serie de bitácora inmutable correspondiente al contexto de donación.
CREATE TABLE registro_auditoria (
    id UUID PRIMARY KEY,
    actor_tipo VARCHAR(20) NOT NULL CHECK (actor_tipo IN ('usuario', 'sistema', 'anonimo')),
    actor_id VARCHAR(64) NULL,
    rol VARCHAR(40) NULL,
    jurisdiccion_solicitada VARCHAR(80) NULL,
    operacion VARCHAR(80) NOT NULL,
    recurso_tipo VARCHAR(40) NOT NULL,
    recurso_id UUID NULL,
    resultado VARCHAR(20) NOT NULL CHECK (resultado IN ('permitido', 'denegado')),
    correlacion_id VARCHAR(36) NOT NULL,
    origen VARCHAR(45) NULL,
    ocurrido_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);


-- 7. Tabla de Solo Anexado: consentimiento (M1, Ley 1581, IN-06)
-- Registro histórico de otorgamiento y revocación de tratamiento de datos.
CREATE TABLE consentimiento (
    id UUID PRIMARY KEY,
    donante_id UUID NOT NULL,
    finalidad VARCHAR(40) NOT NULL CHECK (finalidad IN ('tratamiento_datos', 'avisos_campanas')),
    version_aviso VARCHAR(20) NOT NULL,
    otorgado BOOLEAN NOT NULL,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);


-- 8. Tabla de Solo Anexado: reconocimiento_donante (M1, RF-11, EC-14, IN-06)
-- Otorgamiento inmutable de insignias y reconocimientos simbólicos al donante.
CREATE TABLE reconocimiento_donante (
    donante_id UUID NOT NULL,
    reconocimiento_id UUID NOT NULL,
    donacion_id UUID NOT NULL REFERENCES donacion(id),
    otorgado_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (donante_id, reconocimiento_id)
);


-- 9. Tabla Técnica: operacion_idempotente (EC-10, IN-12, IN-18)
-- Registro técnico de idempotencia. Única entidad del dominio con caducidad y borrado físico.
CREATE TABLE operacion_idempotente (
    sujeto VARCHAR(64) NOT NULL,
    clave VARCHAR(64) NOT NULL,
    operacion VARCHAR(80) NOT NULL,
    huella_peticion VARCHAR(64) NOT NULL,
    recurso_id UUID NULL,
    codigo_estado INTEGER NOT NULL,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expira_en TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (sujeto, clave)
);


-- =============================================================================
-- PARTE 2: CONFIGURACIÓN DE ROLES Y PRIVILEGIOS DE SOLO ANEXADO (GRANT / REVOKE)
-- =============================================================================

-- Asignación explícita de propiedad de las tablas al rol donacion_propietario
ALTER TABLE tipo_donacion OWNER TO donacion_propietario;
ALTER TABLE intencion_donacion OWNER TO donacion_propietario;
ALTER TABLE donacion OWNER TO donacion_propietario;
ALTER TABLE unidad OWNER TO donacion_propietario;
ALTER TABLE evento_unidad OWNER TO donacion_propietario;
ALTER TABLE registro_auditoria OWNER TO donacion_propietario;
ALTER TABLE consentimiento OWNER TO donacion_propietario;
ALTER TABLE reconocimiento_donante OWNER TO donacion_propietario;
ALTER TABLE operacion_idempotente OWNER TO donacion_propietario;

-- REVOCAR todos los permisos por defecto sobre el esquema public al rol de servicio en ejecución
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM donacion_servicio;

-- A. TABLAS DE SOLO ANEXADO (Append-Only): Únicamente SELECT e INSERT (PD-06, IN-06, EC-05)
-- El motor PostgreSQL denegará cualquier UPDATE o DELETE ejecutado por la aplicación.
GRANT SELECT, INSERT ON evento_unidad TO donacion_servicio;
GRANT SELECT, INSERT ON registro_auditoria TO donacion_servicio;
GRANT SELECT, INSERT ON consentimiento TO donacion_servicio;
GRANT SELECT, INSERT ON reconocimiento_donante TO donacion_servicio;

-- B. ENTIDADES INMUTABLES DEL DOMINIO: Únicamente SELECT e INSERT (IN-03)
GRANT SELECT, INSERT ON donacion TO donacion_servicio;

-- C. ENTIDADES PARCIALMENTE MUTABLES: Permiso UPDATE restringido a columnas específicas (IN-03)
-- intencion_donacion solo permite actualizar estado y la marca temporal de atención
GRANT SELECT, INSERT ON intencion_donacion TO donacion_servicio;
GRANT UPDATE (estado, atendida_en) ON intencion_donacion TO donacion_servicio;

-- unidad solo permite actualizar el estado, aptitud, custodia y marca de actualización
GRANT SELECT, INSERT ON unidad TO donacion_servicio;
GRANT UPDATE (estado_id, apta, institucion_custodia_id, actualizado_en) ON unidad TO donacion_servicio;

-- D. CATÁLOGOS: Únicamente permiso de lectura (SELECT)
GRANT SELECT ON tipo_donacion TO donacion_servicio;

-- E. TABLA TÉCNICA DE IDEMPOTENCIA: Única tabla con permiso DELETE para depuración física (IN-18)
GRANT SELECT, INSERT, UPDATE, DELETE ON operacion_idempotente TO donacion_servicio;
