-- Numeración independiente FE / NC / ND por punto de emisión.
-- Prefijos NC/ND por defecto evitan colisión con el consecutivo de factura.

ALTER TABLE emission_points
    ADD COLUMN IF NOT EXISTS prefijo_nc VARCHAR(12),
    ADD COLUMN IF NOT EXISTS numero_actual_nc BIGINT,
    ADD COLUMN IF NOT EXISTS prefijo_nd VARCHAR(12),
    ADD COLUMN IF NOT EXISTS numero_actual_nd BIGINT;

UPDATE emission_points
SET prefijo_nc = COALESCE(NULLIF(BTRIM(prefijo_nc), ''), 'NC'),
    numero_actual_nc = COALESCE(numero_actual_nc, GREATEST(rango_desde - 1, 0)),
    prefijo_nd = COALESCE(NULLIF(BTRIM(prefijo_nd), ''), 'ND'),
    numero_actual_nd = COALESCE(numero_actual_nd, GREATEST(rango_desde - 1, 0))
WHERE prefijo_nc IS NULL
   OR numero_actual_nc IS NULL
   OR prefijo_nd IS NULL
   OR numero_actual_nd IS NULL;

ALTER TABLE emission_points
    ALTER COLUMN prefijo_nc SET DEFAULT 'NC',
    ALTER COLUMN prefijo_nd SET DEFAULT 'ND',
    ALTER COLUMN numero_actual_nc SET DEFAULT 0,
    ALTER COLUMN numero_actual_nd SET DEFAULT 0;

ALTER TABLE emission_points
    ALTER COLUMN prefijo_nc SET NOT NULL,
    ALTER COLUMN numero_actual_nc SET NOT NULL,
    ALTER COLUMN prefijo_nd SET NOT NULL,
    ALTER COLUMN numero_actual_nd SET NOT NULL;

COMMENT ON COLUMN emission_points.prefijo_nc IS 'Prefijo de numeración para notas crédito (91)';
COMMENT ON COLUMN emission_points.numero_actual_nc IS 'Último consecutivo asignado a notas crédito';
COMMENT ON COLUMN emission_points.prefijo_nd IS 'Prefijo de numeración para notas débito (92)';
COMMENT ON COLUMN emission_points.numero_actual_nd IS 'Último consecutivo asignado a notas débito';
