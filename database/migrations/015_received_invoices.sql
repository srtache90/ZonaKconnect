-- 015: facturas recibidas en tabla propia (sin mezclar con emisión)
CREATE TABLE IF NOT EXISTS received_invoices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL,
    supplier_name VARCHAR(255) NOT NULL DEFAULT 'Proveedor',
    supplier_nit VARCHAR(32) NOT NULL DEFAULT '—',
    invoice_number VARCHAR(80) NOT NULL,
    cufe VARCHAR(160),
    issue_date DATE,
    total_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    estado_dian VARCHAR(40) NOT NULL DEFAULT 'PENDIENTE',
    source VARCHAR(80) NOT NULL DEFAULT 'UNKNOWN',
    xml_s3_url TEXT,
    pdf_s3_url TEXT,
    raw_payload_jsonb JSONB NOT NULL DEFAULT '{}'::jsonb,
    dian_response_jsonb JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_received_invoices_tenant_cufe
    ON received_invoices (company_id, cufe)
    WHERE cufe IS NOT NULL AND cufe <> '';

CREATE INDEX IF NOT EXISTS idx_received_invoices_tenant_created
    ON received_invoices (company_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_received_invoices_tenant_estado
    ON received_invoices (company_id, estado_dian, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_received_invoices_tenant_supplier
    ON received_invoices (company_id, supplier_nit);

CREATE INDEX IF NOT EXISTS idx_received_invoices_dian_response_gin
    ON received_invoices USING GIN (dian_response_jsonb);

CREATE INDEX IF NOT EXISTS idx_received_invoices_payload_gin
    ON received_invoices USING GIN (raw_payload_jsonb);

-- Migrar filas que se habían colado en invoices (RCV / sin punto de emisión / role RECIBIDA)
INSERT INTO received_invoices (
    id, company_id, supplier_name, supplier_nit, invoice_number, cufe,
    issue_date, total_amount, estado_dian, source,
    xml_s3_url, pdf_s3_url, raw_payload_jsonb, dian_response_jsonb,
    created_at, updated_at
)
SELECT
    i.id,
    i.company_id,
    COALESCE(
        NULLIF(i.raw_dian_payload_jsonb->'proveedor'->>'razon_social', ''),
        NULLIF(i.raw_dian_payload_jsonb->'proveedor'->>'nombre', ''),
        'Proveedor'
    ),
    COALESCE(
        NULLIF(i.raw_dian_payload_jsonb->'proveedor'->>'nit', ''),
        '—'
    ),
    COALESCE(
        NULLIF(i.raw_dian_payload_jsonb->>'invoice_number', ''),
        NULLIF(i.prefijo, '') || i.numero::text,
        'SIN-NUMERO'
    ),
    NULLIF(COALESCE(i.uuid_cude, i.raw_dian_payload_jsonb->>'cufe', ''), ''),
    CASE
        WHEN COALESCE(i.raw_dian_payload_jsonb->>'fecha_emision', '') ~ '^\d{4}-\d{2}-\d{2}'
            THEN (i.raw_dian_payload_jsonb->>'fecha_emision')::date
        ELSE (i.created_at AT TIME ZONE 'America/Bogota')::date
    END,
    COALESCE(
        NULLIF(i.totals_jsonb->>'total', '')::numeric,
        NULLIF(i.raw_dian_payload_jsonb->>'total', '')::numeric,
        0
    ),
    COALESCE(NULLIF(i.estado_dian, ''), 'PENDIENTE'),
    COALESCE(
        NULLIF(i.raw_dian_payload_jsonb->>'source', ''),
        CASE WHEN i.prefijo = 'RCV' THEN 'LEGACY_INVOICES' ELSE 'LEGACY_INVOICES' END
    ),
    i.xml_s3_url,
    i.pdf_s3_url,
    COALESCE(i.raw_dian_payload_jsonb, '{}'::jsonb),
    COALESCE(i.dian_response_jsonb, '{}'::jsonb),
    i.created_at,
    i.updated_at
FROM invoices i
WHERE i.emission_point_id IS NULL
   OR i.prefijo = 'RCV'
   OR COALESCE(i.raw_dian_payload_jsonb->>'role', '') = 'RECIBIDA'
   OR COALESCE(i.raw_dian_payload_jsonb->>'source', '') IN ('MAIL_INBOX', 'XML_UPLOAD', 'WEBHOOK_S3')
ON CONFLICT (id) DO NOTHING;

DELETE FROM invoices i
WHERE i.emission_point_id IS NULL
   OR i.prefijo = 'RCV'
   OR COALESCE(i.raw_dian_payload_jsonb->>'role', '') = 'RECIBIDA'
   OR COALESCE(i.raw_dian_payload_jsonb->>'source', '') IN ('MAIL_INBOX', 'XML_UPLOAD', 'WEBHOOK_S3');
