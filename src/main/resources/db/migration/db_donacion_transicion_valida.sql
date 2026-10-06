-- =============================================================================
-- REDVITAL - Plataforma Nacional de Gestión de Bancos de Sangre
-- Servicio de Donación (db_donacion - PostgreSQL 16)
-- Script de Migración Flyway: Catálogos estado_unidad y transicion_valida
-- Archivo: V2__catalogo_transicion_valida.sql
-- =============================================================================

-- =============================================================================
-- 1. CREACIÓN DE TABLAS DE CATÁLOGO
-- =============================================================================

-- 1.1 Catálogo estado_unidad (M3, EC-04, EC-12, Tabla 18)
-- Define los 9 estados posibles de una unidad de sangre.
-- La columna 'cuenta_disponible' es el mecanismo de control para EC-12.
CREATE TABLE IF NOT EXISTS estado_unidad (
    id UUID PRIMARY KEY,
    codigo VARCHAR(40) NOT NULL UNIQUE,
    nombre VARCHAR(80) NOT NULL,
    es_terminal BOOLEAN NOT NULL DEFAULT FALSE,
    cuenta_disponible BOOLEAN NOT NULL DEFAULT FALSE
);

-- 1.2 Catálogo transicion_valida (M3, EC-04, EC-25, EC-36, Tabla 19)
-- Matriz cerrada de transiciones permitidas en la máquina de estados de la unidad.
-- Toda transición ausente en esta tabla está estrictamente prohibida.
CREATE TABLE IF NOT EXISTS transicion_valida (
    id UUID PRIMARY KEY,
    estado_origen_id UUID NOT NULL REFERENCES estado_unidad(id),
    estado_destino_id UUID NOT NULL REFERENCES estado_unidad(id),
    actor_tipo VARCHAR(20) NOT NULL CHECK (actor_tipo IN ('usuario', 'sistema')),
    operacion VARCHAR(120) NOT NULL,
    condicion VARCHAR(280) NULL,
    CONSTRAINT uq_transicion_origen_destino_actor UNIQUE (estado_origen_id, estado_destino_id, actor_tipo)
);

-- =============================================================================
-- 2. POBLADO DE ESTADOS DE LA UNIDAD (9 ESTADOS - TABLA 18 DD V2.0)
-- =============================================================================

INSERT INTO estado_unidad (id, codigo, nombre, es_terminal, cuenta_disponible) VALUES
('b1a20001-0000-0000-0000-000000000001', 'captada',     'Captada',     FALSE, FALSE),
('b1a20002-0000-0000-0000-000000000002', 'fraccionada', 'Fraccionada', FALSE, FALSE),
('b1a20003-0000-0000-0000-000000000003', 'en_tamizaje', 'En Tamizaje', FALSE, FALSE),
('b1a20004-0000-0000-0000-000000000004', 'disponible',  'Disponible',  FALSE, TRUE),  -- Único estado que cuenta como existencia (EC-12)
('b1a20005-0000-0000-0000-000000000005', 'reservada',   'Reservada',   FALSE, FALSE),
('b1a20006-0000-0000-0000-000000000006', 'despachada',  'Despachada',  TRUE,  FALSE),  -- Estado terminal
('b1a20007-0000-0000-0000-000000000007', 'no_apta',     'No Apta',     FALSE, FALSE),
('b1a20008-0000-0000-0000-000000000008', 'vencida',     'Vencida',     FALSE, FALSE),
('b1a20009-0000-0000-0000-000000000009', 'desechada',   'Desechada',   TRUE,  FALSE)   -- Estado terminal
ON CONFLICT (codigo) DO UPDATE 
SET nombre = EXCLUDED.nombre,
    es_terminal = EXCLUDED.es_terminal,
    cuenta_disponible = EXCLUDED.cuenta_disponible;

-- =============================================================================
-- 3. POBLADO DE LA MATRIZ CERRADA DE 13 TRANSICIONES VÁLIDAS (TABLA 19 DD V2.0)
-- =============================================================================

INSERT INTO transicion_valida (id, estado_origen_id, estado_destino_id, actor_tipo, operacion, condicion) VALUES

-- #1: captada -> fraccionada (usuario)
('c1b20001-0000-0000-0000-000000000001',
 (SELECT id FROM estado_unidad WHERE codigo = 'captada'),
 (SELECT id FROM estado_unidad WHERE codigo = 'fraccionada'),
 'usuario',
 'POST /v1/donaciones/{id}/fraccionamiento',
 'Todas las unidades de la donación a la vez. RF-03.'),

-- #2: fraccionada -> en_tamizaje (usuario)
('c1b20002-0000-0000-0000-000000000002',
 (SELECT id FROM estado_unidad WHERE codigo = 'fraccionada'),
 (SELECT id FROM estado_unidad WHERE codigo = 'en_tamizaje'),
 'usuario',
 'POST /v1/unidades/{id}/ingreso-tamizaje',
 'RF-05.'),

-- #3: en_tamizaje -> disponible (usuario)
('c1b20003-0000-0000-0000-000000000003',
 (SELECT id FROM estado_unidad WHERE codigo = 'en_tamizaje'),
 (SELECT id FROM estado_unidad WHERE codigo = 'disponible'),
 'usuario',
 'POST /v1/unidades/{id}/tamizaje',
 'con apta verdadero. Fija apta. RF-05.'),

-- #4: en_tamizaje -> no_apta (usuario)
('c1b20004-0000-0000-0000-000000000004',
 (SELECT id FROM estado_unidad WHERE codigo = 'en_tamizaje'),
 (SELECT id FROM estado_unidad WHERE codigo = 'no_apta'),
 'usuario',
 'POST /v1/unidades/{id}/tamizaje',
 'con apta falso. Fija apta. Sin causa. RF-05, EC-02.'),

-- #5: disponible -> reservada (usuario)
('c1b20005-0000-0000-0000-000000000005',
 (SELECT id FROM estado_unidad WHERE codigo = 'disponible'),
 (SELECT id FROM estado_unidad WHERE codigo = 'reservada'),
 'usuario',
 'POST /v1/unidades/{id}/reserva, o aprobación de una transferencia',
 'Unidad en custodia de la institución del usuario. RF-10.'),

-- #6: reservada -> disponible (usuario)
('c1b20006-0000-0000-0000-000000000006',
 (SELECT id FROM estado_unidad WHERE codigo = 'reservada'),
 (SELECT id FROM estado_unidad WHERE codigo = 'disponible'),
 'usuario',
 'DELETE /v1/unidades/{id}/reserva, cancelación o recepción de una transferencia',
 'En la recepción, cambia además institucion_custodia_id en la misma transacción. RF-10, IN-09.'),

-- #7: reservada -> despachada (usuario)
('c1b20007-0000-0000-0000-000000000007',
 (SELECT id FROM estado_unidad WHERE codigo = 'reservada'),
 (SELECT id FROM estado_unidad WHERE codigo = 'despachada'),
 'usuario',
 'POST /v1/unidades/{id}/despacho',
 'Confirmación explícita con el identificador de la unidad; solo la institución custodia. EC-19, EC-39, EC-41.'),

-- #8: disponible -> vencida (sistema)
('c1b20008-0000-0000-0000-000000000008',
 (SELECT id FROM estado_unidad WHERE codigo = 'disponible'),
 (SELECT id FROM estado_unidad WHERE codigo = 'vencida'),
 'sistema',
 'Proceso de vencimiento',
 'Fecha de vencimiento alcanzada. RF-18, EC-13.'),

-- #9: reservada -> vencida (sistema)
('c1b20009-0000-0000-0000-000000000009',
 (SELECT id FROM estado_unidad WHERE codigo = 'reservada'),
 (SELECT id FROM estado_unidad WHERE codigo = 'vencida'),
 'sistema',
 'Proceso de vencimiento',
 'Una unidad reservada también vence.'),

-- #10: fraccionada -> vencida (sistema)
('c1b20010-0000-0000-0000-000000000010',
 (SELECT id FROM estado_unidad WHERE codigo = 'fraccionada'),
 (SELECT id FROM estado_unidad WHERE codigo = 'vencida'),
 'sistema',
 'Proceso de vencimiento',
 'Una unidad sin tamizar también caduca.'),

-- #11: en_tamizaje -> vencida (sistema)
('c1b20011-0000-0000-0000-000000000011',
 (SELECT id FROM estado_unidad WHERE codigo = 'en_tamizaje'),
 (SELECT id FROM estado_unidad WHERE codigo = 'vencida'),
 'sistema',
 'Proceso de vencimiento',
 'Una unidad en tamizaje también vence.'),

-- #12: no_apta -> desechada (usuario)
('c1b20012-0000-0000-0000-000000000012',
 (SELECT id FROM estado_unidad WHERE codigo = 'no_apta'),
 (SELECT id FROM estado_unidad WHERE codigo = 'desechada'),
 'usuario',
 'POST /v1/unidades/{id}/disposicion-final',
 'Confirmación explícita. RF-06, EC-19, EC-41.'),

-- #13: vencida -> desechada (usuario)
('c1b20013-0000-0000-0000-000000000013',
 (SELECT id FROM estado_unidad WHERE codigo = 'vencida'),
 (SELECT id FROM estado_unidad WHERE codigo = 'desechada'),
 'usuario',
 'POST /v1/unidades/{id}/disposicion-final',
 'Confirmación explícita. RF-06.')

ON CONFLICT (id) DO UPDATE 
SET operacion = EXCLUDED.operacion,
    condicion = EXCLUDED.condicion;

-- =============================================================================
-- 4. SEGURIDAD Y PERMISOS DE ROLES (POSTGRESQL)
-- =============================================================================

-- Asignación de propiedad al rol de migraciones
ALTER TABLE estado_unidad OWNER TO donacion_propietario;
ALTER TABLE transicion_valida OWNER TO donacion_propietario;

-- Otorgar únicamente lectura (SELECT) al rol de servicio en tiempo de ejecución
REVOKE ALL ON estado_unidad FROM donacion_servicio;
REVOKE ALL ON transicion_valida FROM donacion_servicio;

GRANT SELECT ON estado_unidad TO donacion_servicio;
GRANT SELECT ON transicion_valida TO donacion_servicio;
