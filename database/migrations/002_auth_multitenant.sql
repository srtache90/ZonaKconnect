CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;

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

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'sociedades'
          AND column_name = 'dian_regimen_fiscal'
    ) THEN
        ALTER TABLE sociedades ALTER COLUMN dian_regimen_fiscal SET DEFAULT 'ZZ';
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
            dian_regimen_fiscal
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
            'ZZ'
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
            password_smtp_enc = EXCLUDED.password_smtp_enc;
    ELSE
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
            password_smtp_enc
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
            'LOCAL_ENCRYPTED_PLACEHOLDER'
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
            password_smtp_enc = EXCLUDED.password_smtp_enc;
    END IF;
END $$;

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
