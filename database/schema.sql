CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE companies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nit VARCHAR(20) NOT NULL,
    dv CHAR(1) NOT NULL,
    razon_social VARCHAR(255) NOT NULL,
    nombre_comercial VARCHAR(255),
    email CITEXT,
    telefono VARCHAR(50),
    direccion JSONB NOT NULL DEFAULT '{}'::jsonb,
    dian_config JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (nit, dv)
);

CREATE TABLE emission_points (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
    codigo VARCHAR(50) NOT NULL,
    nombre VARCHAR(160) NOT NULL,
    direccion VARCHAR(255),
    prefijo VARCHAR(12) NOT NULL,
    resolucion_dian VARCHAR(80) NOT NULL,
    clave_tecnica VARCHAR(255),
    rango_desde BIGINT NOT NULL,
    rango_hasta BIGINT NOT NULL,
    numero_actual BIGINT NOT NULL,
    vigencia_desde DATE NOT NULL,
    vigencia_hasta DATE NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (rango_desde <= rango_hasta),
    CHECK (numero_actual >= rango_desde - 1 AND numero_actual <= rango_hasta),
    UNIQUE (company_id, codigo),
    UNIQUE (company_id, prefijo, resolucion_dian)
);

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
    email CITEXT NOT NULL,
    password_hash TEXT NOT NULL,
    role VARCHAR(40) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (company_id, email)
);

CREATE TABLE invoices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
    emission_point_id UUID REFERENCES emission_points(id) ON DELETE RESTRICT,
    uuid_cude VARCHAR(160),
    prefijo VARCHAR(12) NOT NULL,
    numero BIGINT NOT NULL,
    estado_dian VARCHAR(40) NOT NULL DEFAULT 'PENDIENTE',
    xml_s3_url TEXT,
    pdf_s3_url TEXT,
    dian_response_jsonb JSONB NOT NULL DEFAULT '{}'::jsonb,
    raw_dian_payload_jsonb JSONB NOT NULL DEFAULT '{}'::jsonb,
    totals_jsonb JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (company_id, prefijo, numero),
    UNIQUE (company_id, uuid_cude)
);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
    actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    entity_type VARCHAR(80) NOT NULL,
    entity_id UUID NOT NULL,
    action VARCHAR(80) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_companies_active_nit ON companies (is_active, nit);

CREATE INDEX idx_emission_points_tenant_active
    ON emission_points (company_id, is_active, prefijo);

CREATE INDEX idx_emission_points_tenant_vigencia
    ON emission_points (company_id, vigencia_desde, vigencia_hasta);

CREATE INDEX idx_users_tenant_active_role
    ON users (company_id, is_active, role);

CREATE INDEX idx_invoices_tenant_created
    ON invoices (company_id, created_at DESC);

CREATE INDEX idx_invoices_tenant_emission_created
    ON invoices (company_id, emission_point_id, created_at DESC);

CREATE INDEX idx_invoices_tenant_estado_created
    ON invoices (company_id, estado_dian, created_at DESC);

CREATE INDEX idx_invoices_tenant_number
    ON invoices (company_id, prefijo, numero DESC);

CREATE INDEX idx_invoices_dian_response_gin
    ON invoices USING GIN (dian_response_jsonb);

CREATE INDEX idx_audit_events_tenant_entity_created
    ON audit_events (company_id, entity_type, entity_id, created_at DESC);

CREATE INDEX idx_audit_events_payload_gin
    ON audit_events USING GIN (payload);

CREATE TABLE IF NOT EXISTS sociedades (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    razon_social VARCHAR(255) NOT NULL,
    nit VARCHAR(20) NOT NULL UNIQUE,
    api_key VARCHAR(128) UNIQUE,
    correo_emision CITEXT,
    correo_recepcion CITEXT,
    host_smtp VARCHAR(255),
    puerto_smtp INTEGER CHECK (puerto_smtp IS NULL OR puerto_smtp BETWEEN 1 AND 65535),
    usuario_smtp VARCHAR(255),
    password_smtp_enc TEXT,
    host_imap VARCHAR(255),
    puerto_imap INTEGER CHECK (puerto_imap IS NULL OR puerto_imap BETWEEN 1 AND 65535),
    usuario_imap VARCHAR(255),
    password_imap_enc TEXT,
    dian_ambiente VARCHAR(20) NOT NULL DEFAULT 'Habilitacion'
        CHECK (dian_ambiente IN ('Habilitacion', 'Produccion')),
    creado_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS certificados_digitales (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sociedad_id UUID NOT NULL REFERENCES sociedades(id) ON DELETE CASCADE,
    alias VARCHAR(120) NOT NULL,
    contenido_base64_enc TEXT NOT NULL,
    password_enc TEXT NOT NULL,
    valido_hasta DATE NOT NULL,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    creado_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (sociedad_id, alias)
);

CREATE TABLE IF NOT EXISTS usuarios (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username CITEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    rol VARCHAR(20) NOT NULL CHECK (rol IN ('ADMIN', 'OPERADOR')),
    creado_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS usuario_sociedades (
    usuario_id UUID NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    sociedad_id UUID REFERENCES sociedades(id) ON DELETE CASCADE,
    creado_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_usuario_sociedades_usuario_sociedad
    ON usuario_sociedades (usuario_id, sociedad_id)
    WHERE sociedad_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_usuario_sociedades_global
    ON usuario_sociedades (usuario_id)
    WHERE sociedad_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_certificados_sociedad_activo
    ON certificados_digitales (sociedad_id, activo, valido_hasta DESC);

CREATE INDEX IF NOT EXISTS idx_usuario_sociedades_sociedad
    ON usuario_sociedades (sociedad_id);

INSERT INTO sociedades (
    id,
    razon_social,
    nit,
    api_key,
    correo_emision,
    correo_recepcion,
    host_smtp,
    puerto_smtp,
    usuario_smtp,
    password_smtp_enc,
    host_imap,
    puerto_imap,
    usuario_imap,
    password_imap_enc,
    dian_ambiente
)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'Sociedad Local Zona K',
    '900123456',
    'local-sap-simphony-api-key',
    'emision@zonak.local',
    'recepcion@zonak.local',
    'smtp.zonak.local',
    587,
    'smtp-local',
    'LOCAL_ENCRYPTED_PLACEHOLDER',
    'imap.zonak.local',
    993,
    'imap-local',
    'LOCAL_ENCRYPTED_PLACEHOLDER',
    'Habilitacion'
)
ON CONFLICT (id) DO UPDATE SET
    razon_social = EXCLUDED.razon_social,
    nit = EXCLUDED.nit,
    api_key = EXCLUDED.api_key,
    correo_emision = EXCLUDED.correo_emision,
    correo_recepcion = EXCLUDED.correo_recepcion,
    host_smtp = EXCLUDED.host_smtp,
    puerto_smtp = EXCLUDED.puerto_smtp,
    usuario_smtp = EXCLUDED.usuario_smtp,
    password_smtp_enc = EXCLUDED.password_smtp_enc,
    host_imap = EXCLUDED.host_imap,
    puerto_imap = EXCLUDED.puerto_imap,
    usuario_imap = EXCLUDED.usuario_imap,
    password_imap_enc = EXCLUDED.password_imap_enc,
    dian_ambiente = EXCLUDED.dian_ambiente;

INSERT INTO usuarios (
    id,
    username,
    password_hash,
    rol
)
VALUES (
    '00000000-0000-0000-0000-000000000010',
    'admin',
    crypt('admin', gen_salt('bf', 10)),
    'ADMIN'
)
ON CONFLICT (username) DO UPDATE SET
    password_hash = EXCLUDED.password_hash,
    rol = EXCLUDED.rol;

INSERT INTO usuario_sociedades (usuario_id, sociedad_id)
SELECT u.id, NULL
FROM usuarios u
WHERE u.username = 'admin'
ON CONFLICT DO NOTHING;

INSERT INTO companies (
    id,
    nit,
    dv,
    razon_social,
    email,
    direccion,
    dian_config,
    is_active
)
SELECT
    id,
    nit,
    '0',
    razon_social,
    correo_emision,
    '{}'::jsonb,
    jsonb_build_object(
        'ambiente', dian_ambiente,
        's3_certificate_key', 'dian/certificates/local-test.p12',
        'secrets_manager_password_key', 'dian/certificates/test-passwords'
    ),
    true
FROM sociedades
WHERE id = '00000000-0000-0000-0000-000000000001'
ON CONFLICT (id) DO UPDATE SET
    nit = EXCLUDED.nit,
    razon_social = EXCLUDED.razon_social,
    email = EXCLUDED.email,
    dian_config = jsonb_set(
        COALESCE(companies.dian_config, '{}'::jsonb)
            || jsonb_build_object(
                's3_certificate_key', 'dian/certificates/local-test.p12',
                'secrets_manager_password_key', 'dian/certificates/test-passwords'
            ),
        '{ambiente}',
        EXCLUDED.dian_config -> 'ambiente',
        true
    ),
    updated_at = now();

INSERT INTO emission_points (
    id,
    company_id,
    codigo,
    nombre,
    direccion,
    prefijo,
    resolucion_dian,
    clave_tecnica,
    rango_desde,
    rango_hasta,
    numero_actual,
    vigencia_desde,
    vigencia_hasta,
    is_active
)
VALUES (
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000000001',
    'PV-LOCAL',
    'Punto de venta local',
    'Direccion local',
    'EPR',
    '18764000000000',
    'CLAVE-TECNICA-LOCAL',
    1,
    99999999,
    0,
    CURRENT_DATE - 1,
    CURRENT_DATE + 3650,
    true
)
ON CONFLICT (id) DO UPDATE SET
    nombre = EXCLUDED.nombre,
    direccion = EXCLUDED.direccion,
    prefijo = EXCLUDED.prefijo,
    resolucion_dian = EXCLUDED.resolucion_dian,
    clave_tecnica = EXCLUDED.clave_tecnica,
    rango_desde = EXCLUDED.rango_desde,
    rango_hasta = EXCLUDED.rango_hasta,
    numero_actual = emission_points.numero_actual,
    vigencia_desde = EXCLUDED.vigencia_desde,
    vigencia_hasta = EXCLUDED.vigencia_hasta,
    is_active = EXCLUDED.is_active,
    updated_at = now();
