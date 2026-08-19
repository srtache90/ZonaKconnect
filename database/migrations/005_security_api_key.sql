ALTER TABLE sociedades
    ADD COLUMN IF NOT EXISTS api_key VARCHAR(128);

ALTER TABLE usuario_sociedades
    DROP CONSTRAINT IF EXISTS usuario_sociedades_pkey;

ALTER TABLE usuario_sociedades
    ALTER COLUMN sociedad_id DROP NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_sociedades_api_key
    ON sociedades (api_key)
    WHERE api_key IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_usuario_sociedades_usuario_sociedad
    ON usuario_sociedades (usuario_id, sociedad_id)
    WHERE sociedad_id IS NOT NULL;

UPDATE sociedades
SET api_key = 'local-sap-simphony-api-key'
WHERE id = '00000000-0000-0000-0000-000000000001'
  AND api_key IS NULL;

INSERT INTO usuario_sociedades (usuario_id, sociedad_id)
SELECT id, NULL
FROM usuarios
WHERE username = 'admin'
ON CONFLICT DO NOTHING;

