package com.zonak.portal.recepcion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class ReceivedInvoiceRepository {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ReceivedInvoiceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<ReceivedInvoiceRow> findBySociedad(UUID sociedadId) {
        return findReceived(sociedadId, null, null, null);
    }

    public List<ReceivedInvoiceRow> findReceived(
            UUID sociedadId,
            LocalDate fromDate,
            LocalDate toDate,
            String estadoDian
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT i.id,
                       i.prefijo,
                       i.numero,
                       i.uuid_cude,
                       i.estado_dian,
                       i.pdf_s3_url,
                       i.xml_s3_url,
                       i.created_at,
                       i.raw_dian_payload_jsonb::text AS raw_payload,
                       i.totals_jsonb::text AS totals_payload
                FROM invoices i
                WHERE i.company_id = ?
                  AND (
                        i.emission_point_id IS NULL
                        OR COALESCE(i.raw_dian_payload_jsonb->>'role', '') = 'RECIBIDA'
                        OR COALESCE(i.raw_dian_payload_jsonb->>'source', '') = 'MAIL_INBOX'
                  )
                """);
        List<Object> params = new ArrayList<>();
        params.add(sociedadId);

        if (fromDate != null) {
            sql.append(" AND i.created_at::date >= ?");
            params.add(fromDate);
        }
        if (toDate != null) {
            sql.append(" AND i.created_at::date <= ?");
            params.add(toDate);
        }
        if (StringUtils.hasText(estadoDian)) {
            sql.append(" AND i.estado_dian ILIKE ?");
            params.add("%" + estadoDian.trim() + "%");
        }
        sql.append(" ORDER BY i.created_at DESC");

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapRow(rs), params.toArray());
    }

    public Optional<String> findXmlBase(UUID sociedadId, UUID invoiceId) {
        List<String> xmls = jdbcTemplate.query(
                """
                        SELECT COALESCE(i.raw_dian_payload_jsonb->>'xml_base', '') AS xml_content
                        FROM invoices i
                        WHERE i.company_id = ?
                          AND i.id = ?
                        """,
                (rs, rowNum) -> rs.getString("xml_content"),
                sociedadId,
                invoiceId
        );
        return xmls.stream().filter(StringUtils::hasText).findFirst();
    }

    private ReceivedInvoiceRow mapRow(ResultSet rs) throws SQLException {
        JsonNode rawPayload = readTree(rs.getString("raw_payload"));
        JsonNode totalsPayload = readTree(rs.getString("totals_payload"));
        String prefijo = safe(rs.getString("prefijo"));
        long numero = rs.getLong("numero");
        OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
        String pdfS3Url = safe(rs.getString("pdf_s3_url"));
        String xmlS3Url = safe(rs.getString("xml_s3_url"));
        String xmlBase = text(rawPayload, "xml_base", "");
        boolean documentsAvailable = !pdfS3Url.isBlank() || !xmlS3Url.isBlank() || !xmlBase.isBlank();
        String cufe = firstText(safe(rs.getString("uuid_cude")), text(rawPayload, "cufe", ""));
        String fecha = firstText(text(rawPayload, "fecha_emision", ""));
        if (fecha.isBlank() && createdAt != null) {
            fecha = createdAt.toLocalDate().format(DATE_FORMAT);
        }

        return new ReceivedInvoiceRow(
                rs.getObject("id", UUID.class),
                resolveProveedorName(rawPayload),
                resolveProveedorNit(rawPayload),
                firstText(text(rawPayload, "invoice_number", ""), prefijo + numero),
                cufe,
                resolveTotal(totalsPayload, rawPayload),
                fecha,
                RecepcionEstadoDian.fromDb(rs.getString("estado_dian")),
                pdfS3Url,
                xmlS3Url,
                documentsAvailable
        );
    }

    private JsonNode readTree(String value) {
        try {
            if (value == null || value.isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(value);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private String resolveProveedorName(JsonNode rawPayload) {
        return firstText(
                nestedText(rawPayload, "proveedor", "razon_social"),
                nestedText(rawPayload, "proveedor", "nombre"),
                nestedText(rawPayload, "supplier", "razon_social"),
                nestedText(rawPayload, "cliente", "razon_social"),
                "Proveedor"
        );
    }

    private String resolveProveedorNit(JsonNode rawPayload) {
        return firstText(
                nestedText(rawPayload, "proveedor", "nit"),
                nestedText(rawPayload, "proveedor", "numero_identificacion"),
                nestedText(rawPayload, "supplier", "nit"),
                nestedText(rawPayload, "cliente", "numero_identificacion"),
                "—"
        );
    }

    private BigDecimal resolveTotal(JsonNode totalsPayload, JsonNode rawPayload) {
        BigDecimal total = decimal(totalsPayload, "total");
        if (total.compareTo(BigDecimal.ZERO) > 0) {
            return total;
        }
        total = decimal(totalsPayload, "totalAmount");
        if (total.compareTo(BigDecimal.ZERO) > 0) {
            return total;
        }
        total = decimal(rawPayload, "total");
        if (total.compareTo(BigDecimal.ZERO) > 0) {
            return total;
        }
        return decimal(rawPayload.path("totals_jsonb"), "total");
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            return fallback;
        }
        return value.asText();
    }

    private String nestedText(JsonNode parent, String objectField, String field) {
        JsonNode node = parent.path(objectField);
        if (node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return BigDecimal.ZERO;
        }
        try {
            if (value.isNumber()) {
                return value.decimalValue().setScale(2, RoundingMode.HALF_UP);
            }
            String raw = value.asText("").trim().replace(",", ".");
            if (!StringUtils.hasText(raw)) {
                return BigDecimal.ZERO;
            }
            return new BigDecimal(raw).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception ex) {
            return BigDecimal.ZERO;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
