package com.zonak.portal.recepcion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class RadianEventRepository {
    private final JdbcTemplate jdbcTemplate;

    public RadianEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID insert(
            UUID companyId,
            UUID receivedInvoiceId,
            String eventCode,
            String eventAction,
            String eventLabel,
            String cufe,
            String invoiceNumber,
            String supplierName,
            String supplierNit,
            String supplierEmail,
            String estado,
            String trackId,
            String cude,
            String ambiente,
            String dianResponseJson
    ) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO radian_events (
                            company_id, received_invoice_id, event_code, event_action, event_label,
                            cufe, invoice_number, supplier_name, supplier_nit, supplier_email,
                            estado, track_id, cude, ambiente, dian_response_jsonb
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        RETURNING id
                        """,
                UUID.class,
                companyId,
                receivedInvoiceId,
                eventCode,
                eventAction,
                eventLabel,
                cufe,
                invoiceNumber,
                supplierName,
                supplierNit,
                supplierEmail,
                estado,
                trackId,
                cude,
                ambiente,
                dianResponseJson == null ? "{}" : dianResponseJson
        );
    }

    public void updateNotify(UUID companyId, UUID eventId, String status, String detail) {
        jdbcTemplate.update(
                """
                        UPDATE radian_events
                        SET notify_status = ?,
                            notify_detail = ?,
                            notified_at = CASE WHEN ? IN ('ENVIADO', 'ENVIADO_OK') THEN now() ELSE notified_at END
                        WHERE id = ? AND company_id = ?
                        """,
                status,
                detail,
                status,
                eventId,
                companyId
        );
    }

    public List<RadianEventRow> find(
            UUID companyId,
            LocalDate fromDate,
            LocalDate toDate,
            String eventCode,
            String estado
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, received_invoice_id, event_code, event_action, event_label,
                       cufe, invoice_number, supplier_name, supplier_nit, supplier_email,
                       estado, track_id, cude, ambiente, notify_status, notify_detail,
                       notified_at, created_at
                FROM radian_events
                WHERE company_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        if (fromDate != null) {
            sql.append(" AND created_at::date >= ?");
            params.add(fromDate);
        }
        if (toDate != null) {
            sql.append(" AND created_at::date <= ?");
            params.add(toDate);
        }
        if (StringUtils.hasText(eventCode)) {
            sql.append(" AND event_code = ?");
            params.add(eventCode.trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(estado)) {
            sql.append(" AND UPPER(estado) = ?");
            params.add(estado.trim().toUpperCase(Locale.ROOT));
        }
        sql.append(" ORDER BY created_at DESC LIMIT 1000");
        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> new RadianEventRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("received_invoice_id", UUID.class),
                        rs.getString("event_code"),
                        rs.getString("event_action"),
                        rs.getString("event_label"),
                        rs.getString("cufe"),
                        rs.getString("invoice_number"),
                        rs.getString("supplier_name"),
                        rs.getString("supplier_nit"),
                        rs.getString("supplier_email"),
                        rs.getString("estado"),
                        rs.getString("track_id"),
                        rs.getString("cude"),
                        rs.getString("ambiente"),
                        rs.getString("notify_status"),
                        rs.getString("notify_detail"),
                        rs.getObject("notified_at", java.time.OffsetDateTime.class),
                        rs.getObject("created_at", java.time.OffsetDateTime.class)
                ),
                params.toArray()
        );
    }
}
