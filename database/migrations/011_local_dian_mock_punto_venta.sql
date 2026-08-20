INSERT INTO companies (
    id,
    nit,
    dv,
    razon_social,
    email,
    direccion,
    dian_config,
    is_active
)
SELECT
    id,
    nit,
    '0',
    razon_social,
    correo_emision,
    '{}'::jsonb,
    jsonb_build_object(
        'ambiente', dian_ambiente,
        's3_certificate_key', 'dian/certificates/local-test.p12',
        'secrets_manager_password_key', 'dian/certificates/test-passwords'
    ),
    true
FROM sociedades
WHERE id = '00000000-0000-0000-0000-000000000001'
ON CONFLICT (id) DO UPDATE SET
    nit = EXCLUDED.nit,
    razon_social = EXCLUDED.razon_social,
    email = EXCLUDED.email,
    dian_config = jsonb_set(
        COALESCE(companies.dian_config, '{}'::jsonb)
            || jsonb_build_object(
                's3_certificate_key', 'dian/certificates/local-test.p12',
                'secrets_manager_password_key', 'dian/certificates/test-passwords'
            ),
        '{ambiente}',
        EXCLUDED.dian_config -> 'ambiente',
        true
    ),
    updated_at = now();

INSERT INTO emission_points (
    id,
    company_id,
    codigo,
    nombre,
    direccion,
    prefijo,
    resolucion_dian,
    clave_tecnica,
    rango_desde,
    rango_hasta,
    numero_actual,
    vigencia_desde,
    vigencia_hasta,
    is_active
)
VALUES (
    '00000000-0000-0000-0000-000000000101',
    '00000000-0000-0000-0000-000000000001',
    'DIAN-MOCK',
    'Punto de venta DIAN Mock (Local)',
    'Entorno local - pruebas DIAN Habilitacion',
    'EPR',
    '18764000000000',
    'CLAVE-TECNICA-DIAN-MOCK-LOCAL',
    1,
    99999999,
    0,
    CURRENT_DATE - 1,
    CURRENT_DATE + 3650,
    true
)
ON CONFLICT (id) DO UPDATE SET
    company_id = EXCLUDED.company_id,
    codigo = EXCLUDED.codigo,
    nombre = EXCLUDED.nombre,
    direccion = EXCLUDED.direccion,
    prefijo = EXCLUDED.prefijo,
    resolucion_dian = EXCLUDED.resolucion_dian,
    clave_tecnica = EXCLUDED.clave_tecnica,
    rango_desde = EXCLUDED.rango_desde,
    rango_hasta = EXCLUDED.rango_hasta,
    numero_actual = emission_points.numero_actual,
    vigencia_desde = EXCLUDED.vigencia_desde,
    vigencia_hasta = EXCLUDED.vigencia_hasta,
    is_active = EXCLUDED.is_active,
    updated_at = now();

