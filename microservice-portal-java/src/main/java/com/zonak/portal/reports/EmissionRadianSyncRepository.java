package com.zonak.portal.reports;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class EmissionRadianSyncRepository {
    private static final List<String> DEFAULT_KINDS = List.of("INVOICE", "CREDIT_NOTE", "DEBIT_NOTE");
    private static final int SYNC_LIMIT = 200;

    private final JdbcTemplate jdbcTemplate;

    public EmissionRadianSyncRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SyncCandidate> findCandidatesForSync(
            UUID tenantId,
            UUID emissionPointId,
            LocalDate fromDate,
            LocalDate toDate,
            String documentKind,
            String estadoDian
    ) {
        List<String> kinds = resolveKinds(documentKind);
        StringBuilder sql = new StringBuilder("""
                SELECT i.id,
                       COALESCE(NULLIF(i.uuid_cude, ''), i.dian_response_jsonb->>'cufe', i.dian_response_jsonb->>'uuid') AS cufe,
                       CONCAT(COALESCE(i.prefijo, ''), i.numero) AS document_number
                FROM invoices i
                WHERE i.company_id = ?
                  AND i.emission_point_id IS NOT NULL
                  AND i.created_at >= ?::date
                  AND i.created_at < (?::date + INTERVAL '1 day')
                  AND btrim(COALESCE(
                      NULLIF(i.uuid_cude, ''),
                      i.dian_response_jsonb->>'cufe',
                      i.dian_response_jsonb->>'uuid',
                      ''
                  )) <> ''
                """);

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(fromDate);
        params.add(toDate);

        sql.append(" AND COALESCE(NULLIF(i.document_kind, ''), 'INVOICE') IN (");
        sql.append(String.join(", ", kinds.stream().map(kind -> "?").toList()));
        sql.append(")");
        params.addAll(kinds);

        if (emissionPointId != null) {
            sql.append(" AND i.emission_point_id = ?");
            params.add(emissionPointId);
        }
        if (StringUtils.hasText(estadoDian)) {
            sql.append(" AND i.estado_dian ILIKE ?");
            params.add("%" + estadoDian.trim() + "%");
        }

        sql.append(" ORDER BY i.created_at DESC LIMIT ?");
        params.add(SYNC_LIMIT);

        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> new SyncCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getString("cufe"),
                        rs.getString("document_number")
                ),
                params.toArray()
        );
    }

    public void persistDianRadianEvents(
            UUID tenantId,
            UUID invoiceId,
            String eventsJson,
            String dianStatusJson,
            OffsetDateTime syncedAt
    ) {
        jdbcTemplate.update(
                """
                        UPDATE invoices
                        SET dian_response_jsonb = COALESCE(dian_response_jsonb, '{}'::jsonb)
                            || jsonb_build_object(
                                'radian_events', ?::jsonb,
                                'radian_events_synced_at', to_jsonb(?::text),
                                'radian_events_dian_status', ?::jsonb
                            )
                        WHERE id = ?
                          AND company_id = ?
                        """,
                eventsJson,
                syncedAt.toString(),
                dianStatusJson,
                invoiceId,
                tenantId
        );
    }

    private List<String> resolveKinds(String documentKind) {
        if (!StringUtils.hasText(documentKind)) {
            return DEFAULT_KINDS;
        }
        return switch (documentKind.trim().toUpperCase()) {
            case "INVOICE", "FV", "FACTURA" -> List.of("INVOICE");
            case "CREDIT_NOTE", "NC", "NOTA_CREDITO" -> List.of("CREDIT_NOTE");
            case "DEBIT_NOTE", "ND", "NOTA_DEBITO" -> List.of("DEBIT_NOTE");
            default -> DEFAULT_KINDS;
        };
    }

    public record SyncCandidate(UUID id, String cufe, String documentNumber) {
    }
}
