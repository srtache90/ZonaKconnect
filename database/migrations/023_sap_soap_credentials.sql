-- Credenciales SOAP Dispapeles (enviarDocumento) por sociedad.
-- SAP autentica con idEmpresa + usuario + contraseña embebidos en el XML.

ALTER TABLE sociedades
    ADD COLUMN IF NOT EXISTS id_empresa INTEGER,
    ADD COLUMN IF NOT EXISTS sap_usuario VARCHAR(120),
    ADD COLUMN IF NOT EXISTS sap_password_enc TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS uq_sociedades_id_empresa
    ON sociedades (id_empresa)
    WHERE id_empresa IS NOT NULL;

COMMENT ON COLUMN sociedades.id_empresa IS 'Código numérico que SAP envía en felCabezaDocumento/idEmpresa';
COMMENT ON COLUMN sociedades.sap_usuario IS 'Usuario SOAP embebido en felCabezaDocumento/usuario';
COMMENT ON COLUMN sociedades.sap_password_enc IS 'Contraseña SOAP cifrada (AES-GCM) o LOCAL_<plaintext> en entorno local';

UPDATE sociedades
SET id_empresa = COALESCE(id_empresa, 1),
    sap_usuario = COALESCE(NULLIF(BTRIM(sap_usuario), ''), 'ULocalSap'),
    sap_password_enc = COALESCE(NULLIF(BTRIM(sap_password_enc), ''), 'LOCAL_SapMock2026!')
WHERE id = '00000000-0000-0000-0000-000000000001';
