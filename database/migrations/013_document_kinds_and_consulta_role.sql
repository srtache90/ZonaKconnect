-- document_kind for invoices (INVOICE, CREDIT_NOTE, SUPPORT, PAYROLL)
ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS document_kind VARCHAR(40) NOT NULL DEFAULT 'INVOICE';

UPDATE invoices
SET document_kind = CASE
    WHEN emission_point_id IS NULL THEN 'INVOICE'
    WHEN raw_dian_payload_jsonb ? 'credit_note_type_code' THEN 'CREDIT_NOTE'
    WHEN raw_dian_payload_jsonb ? 'trabajador' THEN 'PAYROLL'
    WHEN raw_dian_payload_jsonb ? 'proveedor' THEN 'SUPPORT'
    ELSE COALESCE(NULLIF(document_kind, ''), 'INVOICE')
END
WHERE TRUE;

CREATE INDEX IF NOT EXISTS idx_invoices_tenant_document_kind_created
    ON invoices (company_id, document_kind, created_at DESC);

-- Allow CONSULTA role for portal users
ALTER TABLE usuarios DROP CONSTRAINT IF EXISTS usuarios_rol_check;
ALTER TABLE usuarios
    ADD CONSTRAINT usuarios_rol_check CHECK (rol IN ('ADMIN', 'OPERADOR', 'CONSULTA'));
