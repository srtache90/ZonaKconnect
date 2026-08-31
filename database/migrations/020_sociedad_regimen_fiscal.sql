ALTER TABLE sociedades

    ADD COLUMN IF NOT EXISTS dian_regimen_fiscal VARCHAR(64) NOT NULL DEFAULT 'ZZ';



ALTER TABLE sociedades

    DROP CONSTRAINT IF EXISTS chk_sociedades_dian_regimen_fiscal;



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

