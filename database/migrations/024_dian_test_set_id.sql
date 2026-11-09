ALTER TABLE sociedades
    ADD COLUMN IF NOT EXISTS dian_test_set_id VARCHAR(64);

UPDATE companies c
SET dian_config = COALESCE(c.dian_config, '{}'::jsonb)
    || jsonb_build_object('test_set_id', s.dian_test_set_id)
FROM sociedades s
WHERE c.id = s.id
  AND NULLIF(TRIM(s.dian_test_set_id), '') IS NOT NULL
  AND NULLIF(TRIM(c.dian_config->>'test_set_id'), '') IS NULL;
