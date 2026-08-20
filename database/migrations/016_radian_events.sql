-- 016: registro unificado de eventos RADIAN enviados (eje del módulo recepción)
CREATE TABLE IF NOT EXISTS radian_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL,
    received_invoice_id UUID,
    event_code VARCHAR(8) NOT NULL,
    event_action VARCHAR(40) NOT NULL,
    event_label VARCHAR(160) NOT NULL,
    cufe VARCHAR(160) NOT NULL,
    invoice_number VARCHAR(80) NOT NULL,
    supplier_name VARCHAR(255),
    supplier_nit VARCHAR(32),
    supplier_email VARCHAR(255),
    estado VARCHAR(40) NOT NULL DEFAULT 'ENVIADO',
    track_id VARCHAR(120),
    cude VARCHAR(160),
    ambiente VARCHAR(40),
    dian_response_jsonb JSONB NOT NULL DEFAULT '{}'::jsonb,
    notify_status VARCHAR(40) NOT NULL DEFAULT 'OMITIDO',
    notify_detail TEXT,
    notified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_radian_events_tenant_created
    ON radian_events (company_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_radian_events_tenant_code
    ON radian_events (company_id, event_code, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_radian_events_tenant_cufe
    ON radian_events (company_id, cufe);

CREATE INDEX IF NOT EXISTS idx_radian_events_tenant_estado
    ON radian_events (company_id, estado, created_at DESC);

ALTER TABLE received_invoices
    ADD COLUMN IF NOT EXISTS supplier_email VARCHAR(255);
