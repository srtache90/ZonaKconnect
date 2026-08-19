UPDATE invoices
SET estado_dian = 'ENVIADO',
    updated_at = now()
WHERE uuid_cude IS NOT NULL
  AND btrim(uuid_cude) <> ''
  AND estado_dian <> 'ENVIADO'
  AND estado_dian NOT IN ('PENDIENTE', 'EN_REINTENTO', 'RECHAZADO_DIAN', 'ERROR_DIAN_NET')
  AND (
      COALESCE(dian_response_jsonb->>'exitoso', 'false') = 'true'
      OR lower(estado_dian) LIKE '%validado%'
      OR lower(estado_dian) LIKE '%exitosamente%'
  );
