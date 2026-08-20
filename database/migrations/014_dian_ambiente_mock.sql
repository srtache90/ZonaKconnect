-- Permite ambiente Mock por sociedad (sin forzar mock global en DIAN_NET).
ALTER TABLE sociedades
    DROP CONSTRAINT IF EXISTS chk_sociedades_dian_ambiente;

ALTER TABLE sociedades
    ADD CONSTRAINT chk_sociedades_dian_ambiente
        CHECK (dian_ambiente IN ('Habilitacion', 'Produccion', 'Mock'));

-- Sociedad local de desarrollo: mock; el resto no se toca.
UPDATE sociedades
SET dian_ambiente = 'Mock'
WHERE id = '00000000-0000-0000-0000-000000000001'
  AND dian_ambiente IS DISTINCT FROM 'Produccion';

UPDATE companies c
SET dian_config = jsonb_set(
        COALESCE(c.dian_config, '{}'::jsonb),
        '{ambiente}',
        to_jsonb(s.dian_ambiente),
        true
)
FROM sociedades s
WHERE c.id = s.id;
