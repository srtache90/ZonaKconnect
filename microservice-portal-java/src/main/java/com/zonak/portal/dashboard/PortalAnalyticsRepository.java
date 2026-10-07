package com.zonak.portal.dashboard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class PortalAnalyticsRepository {
    private static final List<String> MONTH_LABELS = List.of(
            "Ene", "Feb", "Mar", "Abr", "May", "Jun",
            "Jul", "Ago", "Sep", "Oct", "Nov", "Dic"
    );

    private final JdbcTemplate jdbcTemplate;

    public PortalAnalyticsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> dashboardKpis(UUID tenantId) {
        return jdbcTemplate.query(
                """
                        SELECT
                          COUNT(*) FILTER (WHERE emission_point_id IS NOT NULL AND created_at::date = CURRENT_DATE) AS emitted_today,
                          COUNT(*) FILTER (WHERE emission_point_id IS NOT NULL AND date_trunc('month', created_at) = date_trunc('month', now())) AS emitted_month,
                          COUNT(*) FILTER (WHERE emission_point_id IS NOT NULL AND estado_dian IN ('ENVIADO', 'Documento Validado Exitosamente')) AS accepted_dian,
                          COUNT(*) FILTER (WHERE emission_point_id IS NOT NULL AND estado_dian IN ('RECHAZADO_DIAN', 'ERROR_DIAN_NET', 'RECHAZADO')) AS rejected_dian,
                          COUNT(*) FILTER (WHERE emission_point_id IS NULL AND estado_dian IN ('PENDIENTE', 'RECIBIDO_PND', 'ACUSADA_085', 'RECIBIDA_086')) AS pending_reception,
                          COUNT(*) FILTER (WHERE COALESCE(document_kind, 'INVOICE') = 'SUPPORT') AS support_documents,
                          COUNT(*) FILTER (WHERE COALESCE(document_kind, 'INVOICE') = 'PAYROLL') AS payroll_documents
                        FROM invoices
                        WHERE company_id = ?
                        """,
                rs -> {
                    if (!rs.next()) {
                        return emptyKpis();
                    }
                    Map<String, Object> kpis = new LinkedHashMap<>();
                    kpis.put("emitted_today", rs.getLong("emitted_today"));
                    kpis.put("emitted_month", rs.getLong("emitted_month"));
                    kpis.put("accepted_dian", rs.getLong("accepted_dian"));
                    kpis.put("rejected_dian", rs.getLong("rejected_dian"));
                    kpis.put("pending_reception", rs.getLong("pending_reception"));
                    kpis.put("support_documents", rs.getLong("support_documents"));
                    kpis.put("payroll_documents", rs.getLong("payroll_documents"));
                    return kpis;
                },
                tenantId
        );
    }

        public List<MonthlyEmission> monthlyEmissions(UUID tenantId) {
        return jdbcTemplate.query(
            """
                WITH months AS (
                  SELECT generate_series(1, 12) AS month
                )
                SELECT months.month, COUNT(i.id) AS total
                FROM months
                LEFT JOIN invoices i
                  ON i.company_id = ?
                 AND i.emission_point_id IS NOT NULL
                 AND i.created_at >= date_trunc('year', CURRENT_DATE)
                 AND i.created_at < date_trunc('year', CURRENT_DATE) + INTERVAL '1 year'
                 AND EXTRACT(MONTH FROM i.created_at) = months.month
                GROUP BY months.month
                ORDER BY months.month
                """,
            (rs, rowNum) -> new MonthlyEmission(
                MONTH_LABELS.get(rs.getInt("month") - 1),
                rs.getLong("total")
            ),
            tenantId
        );
        }

        public List<RecentActivity> recentActivities(UUID tenantId) {
        return jdbcTemplate.query(
            """
                SELECT prefijo, numero, estado_dian, created_at, updated_at
                FROM invoices
                WHERE company_id = ?
                ORDER BY COALESCE(updated_at, created_at) DESC
                LIMIT 8
                """,
            (rs, rowNum) -> new RecentActivity(
                rs.getString("prefijo") + "-" + rs.getLong("numero"),
                rs.getString("estado_dian"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
            ),
            tenantId
        );
        }

    public List<Map<String, Object>> searchDocuments(UUID tenantId, String q) {
        if (!StringUtils.hasText(q) || q.trim().length() < 2) {
            return List.of();
        }
        String like = "%" + q.trim() + "%";
        return jdbcTemplate.query(
                """
                        SELECT id, tipo, prefijo, numero, uuid_cude, estado_dian, document_kind, nombre
                        FROM (
                            SELECT i.id,
                                   CASE WHEN i.emission_point_id IS NULL THEN 'RECIBIDA' ELSE 'EMITIDA' END AS tipo,
                                   i.prefijo,
                                   i.numero::text AS numero,
                                   COALESCE(i.uuid_cude, '') AS uuid_cude,
                                   i.estado_dian,
                                   COALESCE(NULLIF(i.document_kind, ''), 'INVOICE') AS document_kind,
                                   COALESCE(
                                       i.raw_dian_payload_jsonb->'cliente'->>'razon_social',
                                       i.raw_dian_payload_jsonb->'proveedor'->>'razon_social',
                                       ''
                                   ) AS nombre,
                                   i.created_at
                            FROM invoices i
                            WHERE i.company_id = ?
                              AND (
                                i.prefijo ILIKE ?
                                OR i.numero::text ILIKE ?
                                OR (i.prefijo || i.numero::text) ILIKE ?
                                OR COALESCE(i.uuid_cude, '') ILIKE ?
                                OR COALESCE(i.raw_dian_payload_jsonb->'cliente'->>'razon_social', '') ILIKE ?
                                OR COALESCE(i.raw_dian_payload_jsonb->'cliente'->>'numero_identificacion', '') ILIKE ?
                                OR COALESCE(i.raw_dian_payload_jsonb->'cliente'->>'nit', '') ILIKE ?
                                OR COALESCE(i.raw_dian_payload_jsonb->'proveedor'->>'nit', '') ILIKE ?
                                OR COALESCE(i.raw_dian_payload_jsonb->'proveedor'->>'numero_identificacion', '') ILIKE ?
                                OR EXISTS (
                                    SELECT 1 FROM companies c
                                    WHERE c.id = i.company_id AND c.nit ILIKE ?
                                )
                              )
                            UNION ALL
                            SELECT r.id,
                                   'RECIBIDA' AS tipo,
                                   '' AS prefijo,
                                   r.invoice_number AS numero,
                                   COALESCE(r.cufe, '') AS uuid_cude,
                                   r.estado_dian,
                                   'INVOICE' AS document_kind,
                                   r.supplier_name AS nombre,
                                   r.created_at
                            FROM received_invoices r
                            WHERE r.company_id = ?
                              AND (
                                r.invoice_number ILIKE ?
                                OR COALESCE(r.cufe, '') ILIKE ?
                                OR r.supplier_name ILIKE ?
                                OR r.supplier_nit ILIKE ?
                                OR r.raw_payload_jsonb::text ILIKE ?
                                OR EXISTS (
                                    SELECT 1 FROM companies c
                                    WHERE c.id = r.company_id AND c.nit ILIKE ?
                                )
                              )
                        ) AS documents
                        ORDER BY created_at DESC
                        LIMIT 30
                        """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("tipo", rs.getString("tipo"));
                    row.put("prefijo", rs.getString("prefijo"));
                    row.put("numero", rs.getString("numero"));
                    row.put("uuid_cude", rs.getString("uuid_cude"));
                    row.put("estado_dian", rs.getString("estado_dian"));
                    row.put("document_kind", rs.getString("document_kind"));
                    row.put("nombre", rs.getString("nombre"));
                    return row;
                },
                tenantId,
                like,
                like,
                like,
                like,
                like,
                like,
                like,
                like,
                like,
                like,
                tenantId,
                like,
                like,
                like,
                like,
                like,
                like
        );
    }

    public static Map<String, Object> emptyKpis() {
        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("emitted_today", 0L);
        kpis.put("emitted_month", 0L);
        kpis.put("accepted_dian", 0L);
        kpis.put("rejected_dian", 0L);
        kpis.put("pending_reception", 0L);
        kpis.put("support_documents", 0L);
        kpis.put("payroll_documents", 0L);
        return kpis;
    }

    public record RecentActivity(
            String documentNumber,
            String estadoDian,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record MonthlyEmission(String month, long total) {
    }
}
