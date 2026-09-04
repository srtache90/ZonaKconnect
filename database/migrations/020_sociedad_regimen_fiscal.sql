ALTER TABLE sociedades

    ADD COLUMN IF NOT EXISTS dian_regimen_fiscal VARCHAR(64) NOT NULL DEFAULT 'ZZ';



ALTER TABLE sociedades
    DROP CONSTRAINT IF EXISTS chk_sociedades_dian_regimen_fiscal;

-- schema.sql siembra O-99; el CHECK de 020 no lo admite. Alinear antes de validar.
UPDATE sociedades
SET dian_regimen_fiscal = 'O-23'
WHERE dian_regimen_fiscal IN ('O-48', 'O-49', 'O-99', 'O-23;O-33', 'O-33')
   OR dian_regimen_fiscal IS NULL
   OR dian_regimen_fiscal !~ '^(O-13|O-15|O-23|O-47|ZZ)(;(O-13|O-15|O-23|O-47|ZZ))*$';

ALTER TABLE sociedades
    ADD CONSTRAINT chk_sociedades_dian_regimen_fiscal

    CHECK (

        dian_regimen_fiscal ~ '^(O-13|O-15|O-23|O-47|ZZ)(;(O-13|O-15|O-23|O-47|ZZ))*$'

    );



UPDATE companies c

SET dian_config = COALESCE(c.dian_config, '{}'::jsonb)

    || jsonb_build_object('regimen_fiscal', s.dian_regimen_fiscal),

    updated_at = now()

FROM sociedades s

WHERE c.id = s.id

  AND NULLIF(TRIM(c.dian_config->>'regimen_fiscal'), '') IS NULL;

