package com.zonak.portal.recepcion;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RecepcionTacitAcceptanceService {
    private static final Logger log = LoggerFactory.getLogger(RecepcionTacitAcceptanceService.class);

    private final ReceivedInvoiceRepository receivedInvoiceRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Boolean auditTableExists;

    public RecepcionTacitAcceptanceService(
            ReceivedInvoiceRepository receivedInvoiceRepository,
            JdbcTemplate jdbcTemplate
    ) {
        this.receivedInvoiceRepository = receivedInvoiceRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.auditTableExists = detectAuditTable();
    }

    public TacitResult applyDue(UUID companyId) {
        List<ReceivedInvoiceRow> candidates = receivedInvoiceRepository.findEligibleForTacitAcceptance(companyId);
        int applied = 0;
        List<String> details = new ArrayList<>();
        for (ReceivedInvoiceRow row : candidates) {
            if (row.plazoStatus() != RecepcionPlazoStatus.VENCIDO) {
                continue;
            }
            try {
                String eventJson = """
                        [{"code":"TAC","action":"ACEPTACION_TACITA","label":"Aceptación tácita (3 días hábiles sin 087/088)","at":"%s","trackId":"","cude":"","detail":"Registro local tras vencimiento. Factura=%s límite=%s"}]
                        """.formatted(
                        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC),
                        escapeJson(row.invoiceNumber()),
                        row.plazoLimite() == null ? "" : row.plazoLimite()
                );
                int updated = jdbcTemplate.update(
                        """
                                UPDATE received_invoices
                                SET estado_dian = 'ACEPTADA_TACITA',
                                    dian_response_jsonb = jsonb_set(
                                        jsonb_set(
                                            COALESCE(dian_response_jsonb, '{}'::jsonb),
                                            '{last_radian_event}',
                                            '"TACITA"'::jsonb,
                                            true
                                        ),
                                        '{aceptacion_tacita_at}',
                                        to_jsonb(now()::text),
                                        true
                                    ) || jsonb_build_object(
                                        'radian_events',
                                        COALESCE(dian_response_jsonb->'radian_events', '[]'::jsonb) || ?::jsonb
                                    ),
                                    updated_at = now()
                                WHERE id = ?
                                  AND company_id = ?
                                  AND UPPER(estado_dian) IN ('RECIBIDA_086', '086')
                                """,
                        eventJson,
                        row.id(),
                        companyId
                );
                if (updated > 0) {
                    applied++;
                    insertAudit(companyId, row.id());
                    details.add(row.invoiceNumber());
                }
            } catch (Exception ex) {
                log.warn("No se aplicó aceptación tácita a {}: {}", row.id(), ex.getMessage());
            }
        }
        return new TacitResult(candidates.size(), applied, details);
    }

    public AlertsSummary summarizeAlerts(List<ReceivedInvoiceRow> invoices) {
        int porVencer = 0;
        int vencidos = 0;
        int validation = 0;
        for (ReceivedInvoiceRow row : invoices) {
            if (row.plazoStatus() == RecepcionPlazoStatus.POR_VENCER) {
                porVencer++;
            } else if (row.plazoStatus() == RecepcionPlazoStatus.VENCIDO) {
                vencidos++;
            }
            if (row.validationIssues() != null && !row.validationIssues().isEmpty()) {
                validation++;
            }
        }
        return new AlertsSummary(porVencer, vencidos, validation);
    }

    private void insertAudit(UUID companyId, UUID invoiceId) {
        if (!Boolean.TRUE.equals(auditTableExists)) {
            return;
        }
        try {
            jdbcTemplate.update(
                    """
                            INSERT INTO audit_events (company_id, entity_type, entity_id, action, payload)
                            VALUES (?, 'received_invoice', ?, 'ACEPTACION_TACITA', '{}'::jsonb)
                            """,
                    companyId,
                    invoiceId
            );
        } catch (Exception ex) {
            log.warn("No fue posible auditar aceptación tácita {}: {}", invoiceId, ex.getMessage());
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
            return false;
        }
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record TacitResult(int candidates, int applied, List<String> invoiceNumbers) {
        public String summary() {
            if (applied == 0) {
                return "No hay facturas con plazo de aceptación tácita vencido (" + candidates + " en estado 086).";
            }
            return "Aceptación tácita aplicada a " + applied + " factura(s): "
                    + String.join(", ", invoiceNumbers) + ".";
        }
    }

    public record AlertsSummary(int porVencer, int vencidos, int conValidacion) {
        public boolean hasAlerts() {
            return porVencer > 0 || vencidos > 0 || conValidacion > 0;
        }
    }
}
