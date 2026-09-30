-- Lista DIAN producción (TipoResponsabilidad): O-13, O-15, O-23, O-47, ZZ. O-33 no aplica en FE.

UPDATE sociedades

SET dian_regimen_fiscal = 'O-23'

WHERE dian_regimen_fiscal IN ('O-23;O-33', 'O-33', 'O-99', 'O-48', 'O-49');



UPDATE companies c

SET dian_config = COALESCE(c.dian_config, '{}'::jsonb)

    || jsonb_build_object('regimen_fiscal', s.dian_regimen_fiscal),

    updated_at = now()

FROM sociedades s

WHERE c.id = s.id

  AND s.dian_regimen_fiscal = 'O-23';



ALTER TABLE sociedades
    DROP CONSTRAINT IF EXISTS chk_sociedades_dian_regimen_fiscal;

ALTER TABLE sociedades
    DROP CONSTRAINT IF EXISTS sociedades_dian_regimen_fiscal_check;



ALTER TABLE sociedades

    ADD CONSTRAINT chk_sociedades_dian_regimen_fiscal

    CHECK (

        dian_regimen_fiscal ~ '^(O-13|O-15|O-23|O-47|ZZ)(;(O-13|O-15|O-23|O-47|ZZ))*$'

    );

