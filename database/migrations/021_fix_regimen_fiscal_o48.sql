-- Corrige configuraciones erróneas O-48/O-49 (RUT 48 ≠ TaxLevelCode O-48).

UPDATE sociedades

SET dian_regimen_fiscal = 'O-23'

WHERE dian_regimen_fiscal IN ('O-48', 'O-49', 'O-99', 'O-23;O-33', 'O-33');



UPDATE companies c

SET dian_config = COALESCE(c.dian_config, '{}'::jsonb)

    || jsonb_build_object('regimen_fiscal', s.dian_regimen_fiscal),

    updated_at = now()

FROM sociedades s

WHERE c.id = s.id

  AND s.dian_regimen_fiscal = 'O-23'

  AND COALESCE(c.dian_config->>'regimen_fiscal', '') IN ('O-48', 'O-49', 'O-99', 'O-23;O-33', 'O-33');

