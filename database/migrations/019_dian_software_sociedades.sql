ALTER TABLE sociedades
    ADD COLUMN IF NOT EXISTS dian_software_id VARCHAR(64);

ALTER TABLE sociedades
    ADD COLUMN IF NOT EXISTS dian_software_pin_enc TEXT;

-- Backfill sociedades desde companies solo cuando la columna nueva está vacía.
UPDATE sociedades s
SET dian_software_id = NULLIF(TRIM(c.dian_config->>'software_id'), '')
FROM companies c
WHERE c.id = s.id
  AND NULLIF(TRIM(s.dian_software_id), '') IS NULL
  AND NULLIF(TRIM(c.dian_config->>'software_id'), '') IS NOT NULL;

-- Backfill companies desde sociedades solo cuando dian_config aún no tiene software_id.
-- Evita sobrescribir valores existentes en companies si sociedades ya traía otro dato.
UPDATE companies c
SET dian_config = COALESCE(c.dian_config, '{}'::jsonb)
    || jsonb_build_object('software_id', s.dian_software_id)
FROM sociedades s
WHERE c.id = s.id
  AND NULLIF(TRIM(s.dian_software_id), '') IS NOT NULL
  AND NULLIF(TRIM(c.dian_config->>'software_id'), '') IS NULL;

DO $$
DECLARE
    conflict_count INT;
BEGIN
    SELECT COUNT(*) INTO conflict_count
    FROM companies c
    JOIN sociedades s ON s.id = c.id
    WHERE NULLIF(TRIM(s.dian_software_id), '') IS NOT NULL
      AND NULLIF(TRIM(c.dian_config->>'software_id'), '') IS NOT NULL
      AND TRIM(s.dian_software_id) <> TRIM(c.dian_config->>'software_id');

    IF conflict_count > 0 THEN
        RAISE WARNING '019_dian_software_sociedades: % sociedad(es) con software_id distinto entre sociedades.dian_software_id y companies.dian_config; reconciliar manualmente',
            conflict_count;
    END IF;
END $$;
