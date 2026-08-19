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
    host_imap VARCHAR(255),
    puerto_imap INTEGER CHECK (puerto_imap IS NULL OR puerto_imap BETWEEN 1 AND 65535),
    usuario_imap VARCHAR(255),
    password_imap_enc TEXT,
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

CREATE UNIQUE INDEX IF NOT EXISTS uq_sociedades_api_key
    ON sociedades (api_key)
    WHERE api_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_certificados_sociedad_activo
    ON certificados_digitales (sociedad_id, activo, valido_hasta DESC);

CREATE INDEX IF NOT EXISTS idx_usuario_sociedades_sociedad
    ON usuario_sociedades (sociedad_id);

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

-- Sociedad NULL funciona como comodin: el administrador ve sociedades actuales y futuras.
INSERT INTO usuario_sociedades (usuario_id, sociedad_id)
SELECT id, NULL
FROM usuarios
WHERE username = 'admin'
ON CONFLICT DO NOTHING;

