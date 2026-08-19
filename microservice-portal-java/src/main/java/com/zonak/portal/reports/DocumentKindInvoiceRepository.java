package com.zonak.portal.reports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zonak.portal.dto.DocumentKindInvoiceRow;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class DocumentKindInvoiceRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DocumentKindInvoiceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<DocumentKindInvoiceRow> findByKind(UUID tenantId, String documentKind) {
        return findByKind(tenantId, documentKind, null, null, null);
    }

    public List<DocumentKindInvoiceRow> findByKind(
            UUID tenantId,
            String documentKind,
            LocalDate fromDate,
            LocalDate toDate,
            String estadoDian
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT i.id,
                       i.prefijo,
                       i.numero,
                       i.estado_dian,
                       i.uuid_cude,
                       i.created_at,
                       i.raw_dian_payload_jsonb::text AS raw_payload,
                       i.totals_jsonb::text AS totals_payload,
                       i.dian_response_jsonb::text AS dian_response
                FROM invoices i
                WHERE i.company_id = ?
                  AND COALESCE(NULLIF(i.document_kind, ''), 'INVOICE') = ?
                """);

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(documentKind);

        if (fromDate != null) {
            sql.append(" AND i.created_at >= ?::date");
            params.add(fromDate);
        }
        if (toDate != null) {
            sql.append(" AND i.created_at < (?::date + INTERVAL '1 day')");
            params.add(toDate);
        }
        if (StringUtils.hasText(estadoDian)) {
            sql.append(" AND i.estado_dian ILIKE ?");
            params.add("%" + estadoDian.trim() + "%");
        }

        sql.append(" ORDER BY i.created_at DESC LIMIT 500");

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapRow(rs, documentKind), params.toArray());
    }

    private DocumentKindInvoiceRow mapRow(ResultSet rs, String documentKind) throws SQLException {
        JsonNode raw = readTree(rs.getString("raw_payload"));
        JsonNode totals = readTree(rs.getString("totals_payload"));
        JsonNode dian = readTree(rs.getString("dian_response"));

        String prefijo = safe(rs.getString("prefijo"));
        long numero = rs.getLong("numero");
        String documento = prefijo + numero;

        Party party = resolveParty(raw, documentKind);
        BigDecimal total = firstDecimal(
                totals.path("total"),
                raw.path("totals_jsonb").path("total"),
                raw.path("documentoSoporte").path("totales").path("total"),
                raw.path("nomina").path("pago").path("totalComprobante")
        );

        String cufe = firstText(
                safe(rs.getString("uuid_cude")),
                dian.path("cufe").asText(""),
                dian.path("cufeCune").asText(""),
                dian.path("cune").asText(""),
                dian.path("uuid").asText("")
        );

        return new DocumentKindInvoiceRow(
                rs.getObject("id", UUID.class),
                documento,
                party.name(),
                party.idNumber(),
                total,
                safe(rs.getString("estado_dian")),
                rs.getObject("created_at", OffsetDateTime.class),
                cufe
        );
    }

    private Party resolveParty(JsonNode raw, String documentKind) {
        if ("PAYROLL".equalsIgnoreCase(documentKind)) {
            JsonNode trabajador = firstObject(raw.path("trabajador"), raw.path("nomina").path("trabajador"));
            String nombres = firstText(text(trabajador, "nombres", ""), text(trabajador, "primerNombre", ""));
            String apellidos = firstText(text(trabajador, "apellidos", ""), text(trabajador, "primerApellido", ""));
            String name = (nombres + " " + apellidos).trim();
            if (name.isBlank()) {
                name = text(trabajador, "razonSocial", "Trabajador");
            }
            String id = firstText(
                    text(trabajador, "numeroIdentificacion", ""),
                    text(trabajador, "numero_identificacion", "")
            );
            return new Party(name.isBlank() ? "Trabajador" : name, id);
        }

        JsonNode proveedor = firstObject(raw.path("proveedor"), raw.path("documentoSoporte").path("cliente"));
        JsonNode cliente = raw.path("documentoSoporte").path("cliente");
        String name = firstText(
                text(proveedor, "razon_social", ""),
                text(proveedor, "razonSocial", ""),
                text(cliente, "razonSocial", ""),
                "Proveedor"
        );
        String id = firstText(
                text(proveedor, "nit", ""),
                text(proveedor, "numeroIdentificacion", ""),
                text(cliente, "numeroIdentificacion", "")
        );
        return new Party(name, id);
    }

    private JsonNode firstObject(JsonNode primary, JsonNode fallback) {
        if (primary != null && !primary.isMissingNode() && !primary.isNull() && primary.isObject()) {
            return primary;
        }
        if (fallback != null && !fallback.isMissingNode() && !fallback.isNull() && fallback.isObject()) {
            return fallback;
        }
        return objectMapper.createObjectNode();
    }

    private BigDecimal firstDecimal(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull()) {
                if (node.isNumber()) {
                    return node.decimalValue();
                }
                String text = node.asText("");
                if (StringUtils.hasText(text)) {
                    try {
                        return new BigDecimal(text.trim());
                    } catch (NumberFormatException ignored) {
                        // continue
                    }
                }
            }
        }
        return BigDecimal.ZERO;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String text(JsonNode node, String field, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        String value = node.path(field).asText("");
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private JsonNode readTree(String json) {
        if (!StringUtils.hasText(json)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private record Party(String name, String idNumber) {
    }
}
