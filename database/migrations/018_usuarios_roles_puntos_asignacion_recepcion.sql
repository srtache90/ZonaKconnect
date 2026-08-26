-- 018: roles de portal, alcance por punto de venta y asignación de facturas recibidas.

-- Roles: ADMIN (sociedad), EMISOR, RECEPTOR, CONSULTA. OPERADOR se migra a EMISOR (alias legacy permitido).
ALTER TABLE usuarios DROP CONSTRAINT IF EXISTS usuarios_rol_check;
ALTER TABLE usuarios
    ADD CONSTRAINT usuarios_rol_check
        CHECK (rol IN ('ADMIN', 'OPERADOR', 'EMISOR', 'RECEPTOR', 'CONSULTA'));

UPDATE usuarios
SET rol = 'EMISOR'
WHERE rol = 'OPERADOR';

-- Alcance usuario × sociedad × punto (emission_point_id NULL = todos los puntos de esa sociedad)
CREATE TABLE IF NOT EXISTS usuario_puntos_venta (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id UUID NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    sociedad_id UUID NOT NULL REFERENCES sociedades(id) ON DELETE CASCADE,
    emission_point_id UUID REFERENCES emission_points(id) ON DELETE CASCADE,
    creado_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_usuario_puntos_all_society
    ON usuario_puntos_venta (usuario_id, sociedad_id)
    WHERE emission_point_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_usuario_puntos_point
    ON usuario_puntos_venta (usuario_id, sociedad_id, emission_point_id)
    WHERE emission_point_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_usuario_puntos_usuario
    ON usuario_puntos_venta (usuario_id);

CREATE INDEX IF NOT EXISTS idx_usuario_puntos_sociedad_punto
    ON usuario_puntos_venta (sociedad_id, emission_point_id);

-- Punto por defecto por proveedor (NIT) dentro de la sociedad
CREATE TABLE IF NOT EXISTS supplier_default_points (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES sociedades(id) ON DELETE CASCADE,
    supplier_nit VARCHAR(32) NOT NULL,
    emission_point_id UUID NOT NULL REFERENCES emission_points(id) ON DELETE RESTRICT,
    notes VARCHAR(255),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (company_id, supplier_nit)
);

CREATE INDEX IF NOT EXISTS idx_supplier_default_points_company
    ON supplier_default_points (company_id, is_active);

-- Asignación operativa de facturas recibidas a un punto
ALTER TABLE received_invoices
    ADD COLUMN IF NOT EXISTS assigned_emission_point_id UUID REFERENCES emission_points(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS assignment_source VARCHAR(40) NOT NULL DEFAULT 'UNASSIGNED',
    ADD COLUMN IF NOT EXISTS assigned_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS assigned_by_user_id UUID REFERENCES usuarios(id) ON DELETE SET NULL;

ALTER TABLE received_invoices DROP CONSTRAINT IF EXISTS received_invoices_assignment_source_check;
ALTER TABLE received_invoices
    ADD CONSTRAINT received_invoices_assignment_source_check
        CHECK (assignment_source IN ('UNASSIGNED', 'SUPPLIER_DEFAULT', 'CENTRAL', 'MANUAL', 'MAILBOX'));

CREATE INDEX IF NOT EXISTS idx_received_invoices_assigned_point
    ON received_invoices (company_id, assigned_emission_point_id, created_at DESC);

-- Auditoría de reasignaciones manuales
CREATE TABLE IF NOT EXISTS received_invoice_assignment_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    received_invoice_id UUID NOT NULL REFERENCES received_invoices(id) ON DELETE CASCADE,
    company_id UUID NOT NULL,
    from_emission_point_id UUID,
    to_emission_point_id UUID,
    actor_user_id UUID REFERENCES usuarios(id) ON DELETE SET NULL,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rcv_assignment_audit_invoice
    ON received_invoice_assignment_audit (received_invoice_id, created_at DESC);

COMMENT ON TABLE usuario_puntos_venta IS 'Alcance de puntos de venta por usuario/sociedad; NULL emission_point_id = todos los PV de la sociedad';
COMMENT ON TABLE supplier_default_points IS 'Enrutamiento automático de facturas recibidas por NIT proveedor → punto';
COMMENT ON COLUMN received_invoices.assigned_emission_point_id IS 'Punto operativo responsable de la factura recibida; NULL = cola sin asignar';
