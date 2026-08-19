package com.zonak.portal.recepcion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RecepcionEventService {
    private static final Logger log = LoggerFactory.getLogger(RecepcionEventService.class);
    private static final Set<String> PENDING_STATES = Set.of("PENDIENTE", "RECIBIDO_PND");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Boolean auditTableExists;

    public RecepcionEventService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.auditTableExists = detectAuditTable();
    }

    public String registrarAcuse085(UUID companyId, UUID invoiceId) {
        return transition(
                companyId,
                invoiceId,
                "ACUSADA_085",
                PENDING_STATES,
                "ACUSE_085",
                Map.of(),
                "Acuse de recibo 085 registrado correctamente."
        );
    }

    public String registrarReciboBienes086(UUID companyId, UUID invoiceId, String recibidoPor, String documentoRecibidor) {
        ObjectNode payload = objectMapper.createObjectNode();
        if (recibidoPor != null && !recibidoPor.isBlank()) {
            payload.put("recibidoPor", recibidoPor.trim());
        }
        if (documentoRecibidor != null && !documentoRecibidor.isBlank()) {
            payload.put("documentoRecibidor", documentoRecibidor.trim());
        }
        return transition(
                companyId,
                invoiceId,
                "RECIBIDA_086",
                Set.of("ACUSADA_085"),
                "RECIBO_BIENES_086",
                payload,
                "Recibo de bienes/servicios 086 registrado correctamente."
        );
    }

    public String registrarAceptacion087(UUID companyId, UUID invoiceId) {
        return transition(
                companyId,
                invoiceId,
                "ACEPTADA_087",
                Set.of("RECIBIDA_086"),
                "ACEPTACION_087",
                Map.of(),
                "Aceptación expresa 087 registrada correctamente."
        );
    }

    public String registrarRechazo088(UUID companyId, UUID invoiceId, String motivoRechazo) {
        if (motivoRechazo == null || motivoRechazo.isBlank()) {
            throw new IllegalArgumentException("Debe indicar el motivo de rechazo (motivo_rechazo).");
        }
        return transition(
                companyId,
                invoiceId,
                "RECHAZADA_088",
                Set.of("RECIBIDA_086"),
                "RECHAZO_088",
                Map.of("motivo_rechazo", motivoRechazo.trim()),
                "Reclamo/rechazo 088 registrado correctamente."
        );
    }

    private String transition(
            UUID companyId,
            UUID invoiceId,
            String targetState,
            Set<String> allowedFrom,
            String action,
            Object payload,
            String successMessage
    ) {
        InvoiceState current = loadInvoice(companyId, invoiceId);
        String normalized = normalize(current.estadoDian());
        if (!allowedFrom.contains(normalized)) {
            throw new IllegalStateException(invalidTransitionMessage(normalized, targetState));
        }

        int updated = jdbcTemplate.update(
                """
                        UPDATE invoices
                        SET estado_dian = ?, updated_at = now()
                        WHERE id = ? AND company_id = ? AND emission_point_id IS NULL
                        """,
                targetState,
                invoiceId,
                companyId
        );
        if (updated == 0) {
            throw new IllegalStateException("No fue posible actualizar el estado de la factura recibida.");
        }

        insertAudit(companyId, invoiceId, action, payload);
        return successMessage;
    }

    private InvoiceState loadInvoice(UUID companyId, UUID invoiceId) {
        try {
            return jdbcTemplate.queryForObject(
                    """
                            SELECT estado_dian
                            FROM invoices
                            WHERE id = ? AND company_id = ? AND emission_point_id IS NULL
                            """,
                    (rs, rowNum) -> new InvoiceState(rs.getString("estado_dian")),
                    invoiceId,
                    companyId
            );
        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalArgumentException("Factura recibida no encontrada para la sociedad actual.");
        }
    }

    private void insertAudit(UUID companyId, UUID invoiceId, String action, Object payload) {
        if (!Boolean.TRUE.equals(auditTableExists)) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
            jdbcTemplate.update(
                    """
                            INSERT INTO audit_events (company_id, entity_type, entity_id, action, payload)
                            VALUES (?, 'invoice', ?, ?, ?::jsonb)
                            """,
                    companyId,
                    invoiceId,
                    action,
                    json
            );
        } catch (Exception ex) {
            log.warn("No fue posible registrar audit_events action={} invoiceId={}: {}", action, invoiceId, ex.getMessage());
        }
    }

    private Boolean detectAuditTable() {
        try {
            return jdbcTemplate.queryForObject(
                    """
                            SELECT EXISTS (
                                SELECT 1
                                FROM information_schema.tables
                                WHERE table_schema = 'public' AND table_name = 'audit_events'
                            )
                            """,
                    Boolean.class
            );
        } catch (Exception ex) {
            log.warn("No fue posible verificar tabla audit_events: {}", ex.getMessage());
            return false;
        }
    }

    private String normalize(String estado) {
        if (estado == null || estado.isBlank()) {
            return "PENDIENTE";
        }
        String value = estado.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "085" -> "ACUSADA_085";
            case "086" -> "RECIBIDA_086";
            case "087" -> "ACEPTADA_087";
            case "088", "RECHAZADO" -> "RECHAZADA_088";
            default -> value;
        };
    }

    private String invalidTransitionMessage(String current, String target) {
        return "Transición inválida: el estado actual es " + current + " y no permite pasar a " + target + ".";
    }

    private record InvoiceState(String estadoDian) {
    }
}
