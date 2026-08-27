ALTER TABLE sociedades
    ADD COLUMN IF NOT EXISTS dian_software_id VARCHAR(64);

ALTER TABLE sociedades
    ADD COLUMN IF NOT EXISTS dian_software_pin_enc TEXT;

UPDATE sociedades s
SET dian_software_id = NULLIF(TRIM(c.dian_config->>'software_id'), '')
FROM companies c
WHERE c.id = s.id
  AND s.dian_software_id IS NULL
  AND NULLIF(TRIM(c.dian_config->>'software_id'), '') IS NOT NULL;

UPDATE companies c
SET dian_config = COALESCE(c.dian_config, '{}'::jsonb)
    || CASE
        WHEN NULLIF(TRIM(s.dian_software_id), '') IS NOT NULL
            THEN jsonb_build_object('software_id', s.dian_software_id)
        ELSE '{}'::jsonb
    END
FROM sociedades s
WHERE c.id = s.id
  AND NULLIF(TRIM(s.dian_software_id), '') IS NOT NULL;
