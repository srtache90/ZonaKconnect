ALTER TABLE sociedades
    ADD COLUMN IF NOT EXISTS host_imap VARCHAR(255),
    ADD COLUMN IF NOT EXISTS puerto_imap INTEGER,
    ADD COLUMN IF NOT EXISTS usuario_imap VARCHAR(255),
    ADD COLUMN IF NOT EXISTS password_imap_enc TEXT;

ALTER TABLE sociedades
    DROP CONSTRAINT IF EXISTS chk_sociedades_puerto_imap;

ALTER TABLE sociedades
    ADD CONSTRAINT chk_sociedades_puerto_imap
        CHECK (puerto_imap IS NULL OR puerto_imap BETWEEN 1 AND 65535);

UPDATE sociedades
SET host_imap = COALESCE(host_imap, host_smtp),
    puerto_imap = COALESCE(puerto_imap, 993),
    usuario_imap = COALESCE(usuario_imap, usuario_smtp),
    password_imap_enc = COALESCE(password_imap_enc, password_smtp_enc)
WHERE host_imap IS NULL
   OR puerto_imap IS NULL
   OR usuario_imap IS NULL
   OR password_imap_enc IS NULL;
