ALTER TABLE sociedades
    ADD COLUMN IF NOT EXISTS dian_ambiente VARCHAR(20) NOT NULL DEFAULT 'Habilitacion';

ALTER TABLE sociedades
    DROP CONSTRAINT IF EXISTS chk_sociedades_dian_ambiente;

ALTER TABLE sociedades
    ADD CONSTRAINT chk_sociedades_dian_ambiente
        CHECK (dian_ambiente IN ('Habilitacion', 'Produccion'));

UPDATE sociedades
SET dian_ambiente = 'Habilitacion'
WHERE dian_ambiente IS NULL OR dian_ambiente NOT IN ('Habilitacion', 'Produccion');

UPDATE companies c
SET dian_config = jsonb_set(
        COALESCE(c.dian_config, '{}'::jsonb),
        '{ambiente}',
        to_jsonb(s.dian_ambiente),
        true
)
FROM sociedades s
WHERE c.id = s.id;
