package com.zonak.portal.reports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zonak.portal.dto.SalesDetailReportRow;
import com.zonak.portal.exception.InvoiceStorageException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SalesReportRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SalesReportRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<SalesDetailReportRow> findDetailedSales(
            UUID tenantId,
            UUID emissionPointId,
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
                  AND i.emission_point_id IS NOT NULL
                  AND i.created_at >= ?::date
                  AND i.created_at < (?::date + INTERVAL '1 day')
                """);

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(fromDate);
        params.add(toDate);

        if (emissionPointId != null) {
            sql.append(" AND i.emission_point_id = ?");
            params.add(emissionPointId);
        }

        if (estadoDian != null && !estadoDian.isBlank()) {
            sql.append(" AND i.estado_dian ILIKE ?");
            params.add("%" + estadoDian.trim() + "%");
        }

        sql.append(" ORDER BY i.created_at DESC");

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapRow(rs), params.toArray());
    }

    private SalesDetailReportRow mapRow(ResultSet rs) throws SQLException {
        JsonNode rawPayload = readTree(rs.getString("raw_payload"));
        JsonNode totalsPayload = readTree(rs.getString("totals_payload"));
        JsonNode dianResponse = readTree(rs.getString("dian_response"));
        JsonNode customerNode = rawPayload.path("cliente");

        String prefijo = safe(rs.getString("prefijo"));
        long numero = rs.getLong("numero");
        String documento = prefijo + numero;

        String nit = firstText(
                text(customerNode, "numero_identificacion", ""),
                customerNode.path("numeroIdentificacion").asText("")
        );
        String nombre = text(customerNode, "razon_social", "Cliente");
        String direccion = addressText(customerNode.path("direccion"));
        String email = text(customerNode, "email", "");

        TaxSummary taxes = summarizeTaxes(rawPayload.path("items"));
        BigDecimal propinas = resolvePropinas(rawPayload, totalsPayload);
        TotalsSummary totals = resolveTotals(rawPayload, totalsPayload, taxes, propinas);

        String cufe = firstText(
                safe(rs.getString("uuid_cude")),
                dianResponse.path("cufe").asText(""),
                dianResponse.path("cufeCune").asText(""),
                dianResponse.path("uuid").asText("")
        );

        return new SalesDetailReportRow(
                rs.getObject("id", UUID.class),
                documento,
                rs.getObject("created_at", OffsetDateTime.class),
                nit,
                nombre,
                direccion,
                email,
                totals.baseGravada(),
                totals.baseExenta(),
                taxes.iva(),
                taxes.otrosImpuestos(),
                propinas,
                totals.subtotal(),
                totals.total(),
                safe(rs.getString("estado_dian")),
                cufe
        );
    }

    public List<AggregatedIncomeRow> aggregateIncomeByCustomer(
            UUID tenantId,
            UUID emissionPointId,
            LocalDate fromDate,
            LocalDate toDate
    ) {
        List<SalesDetailReportRow> rows = findDetailedSales(tenantId, emissionPointId, fromDate, toDate, null);
        Map<String, AggregatedIncomeRow> aggregated = new LinkedHashMap<>();

        for (SalesDetailReportRow row : rows) {
            String key = row.nitCliente().isBlank() ? row.nombreCliente() : row.nitCliente();
            aggregated.compute(key, (ignored, current) -> {
                if (current == null) {
                    return new AggregatedIncomeRow(
                            row.nitCliente(),
                            row.nombreCliente(),
                            row.direccionCliente(),
                            row.baseGravada(),
                            row.baseExenta(),
                            row.iva(),
                            row.otrosImpuestos(),
                            row.total()
                    );
                }
                return new AggregatedIncomeRow(
                        current.nit(),
                        current.razonSocial(),
                        current.direccion(),
                        current.ingresosGravados().add(row.baseGravada()),
                        current.ingresosExentos().add(row.baseExenta()),
                        current.iva().add(row.iva()),
                        current.otrosImpuestos().add(row.otrosImpuestos()),
                        current.totalIngresos().add(row.total())
                );
            });
        }

        return new ArrayList<>(aggregated.values());
    }

    public CompanyInfo findCompanyInfo(UUID tenantId) {
        List<CompanyInfo> companies = jdbcTemplate.query(
                """
                        SELECT nit, dv, razon_social
                        FROM companies
                        WHERE id = ?
                        """,
                (rs, rowNum) -> new CompanyInfo(
                        rs.getString("nit"),
                        rs.getString("dv"),
                        rs.getString("razon_social")
                ),
                tenantId
        );
        return companies.isEmpty() ? new CompanyInfo("", "", "") : companies.getFirst();
    }

    private TaxSummary summarizeTaxes(JsonNode itemsNode) {
        BigDecimal iva = BigDecimal.ZERO;
        BigDecimal otros = BigDecimal.ZERO;
        BigDecimal baseGravada = BigDecimal.ZERO;
        BigDecimal baseExenta = BigDecimal.ZERO;

        if (!itemsNode.isArray()) {
            return new TaxSummary(iva, otros, baseGravada, baseExenta);
        }

        for (JsonNode item : itemsNode) {
            BigDecimal cantidad = decimal(item, "cantidad", BigDecimal.ONE);
            BigDecimal valorUnitario = decimal(item, "precio_unitario", BigDecimal.ZERO);
            BigDecimal descuento = decimal(item, "descuento", BigDecimal.ZERO);
            BigDecimal lineBase = money(cantidad.multiply(valorUnitario).subtract(descuento));

            JsonNode taxesNode = item.path("impuestos");
            if (!taxesNode.isArray() || taxesNode.isEmpty()) {
                baseExenta = baseExenta.add(lineBase);
                continue;
            }

            boolean hasNonRetentionTax = false;
            for (JsonNode tax : taxesNode) {
                if (tax.path("esRetencion").asBoolean(false)) {
                    continue;
                }
                hasNonRetentionTax = true;
                BigDecimal value = taxAmount(tax, lineBase);
                if ("01".equals(text(tax, "codigo", ""))) {
                    iva = iva.add(value);
                } else {
                    otros = otros.add(value);
                }
            }

            if (hasNonRetentionTax) {
                baseGravada = baseGravada.add(lineBase);
            } else {
                baseExenta = baseExenta.add(lineBase);
            }
        }

        return new TaxSummary(money(iva), money(otros), money(baseGravada), money(baseExenta));
    }

    private TotalsSummary resolveTotals(
            JsonNode rawPayload,
            JsonNode totalsPayload,
            TaxSummary taxes,
            BigDecimal propinas
    ) {
        JsonNode totalsNode = totalsPayload.isMissingNode() || totalsPayload.isNull()
                ? rawPayload.path("totals_jsonb")
                : totalsPayload;
        if (totalsNode.isMissingNode() || totalsNode.isNull()) {
            totalsNode = rawPayload.path("totales");
        }

        BigDecimal subtotal = firstDecimal(totalsNode, "subtotal", taxes.baseGravada().add(taxes.baseExenta()));
        BigDecimal total = firstDecimal(totalsNode, "total", subtotal.add(taxes.iva()).add(taxes.otrosImpuestos()).add(propinas));

        return new TotalsSummary(
                money(taxes.baseGravada()),
                money(taxes.baseExenta()),
                money(subtotal),
                money(total)
        );
    }

    private BigDecimal resolvePropinas(JsonNode rawPayload, JsonNode totalsPayload) {
        JsonNode totalsNode = totalsPayload.isMissingNode() || totalsPayload.isNull()
                ? rawPayload.path("totals_jsonb")
                : totalsPayload;
        if (totalsNode.isMissingNode() || totalsNode.isNull()) {
            totalsNode = rawPayload.path("totales");
        }

        BigDecimal propina = firstDecimal(totalsNode, "propina", BigDecimal.ZERO);
        propina = firstNonZero(propina, decimal(totalsNode, "totalPropina", BigDecimal.ZERO));
        propina = firstNonZero(propina, decimal(totalsNode, "valorPropina", BigDecimal.ZERO));
        propina = firstNonZero(propina, decimal(totalsNode, "tip", BigDecimal.ZERO));
        propina = firstNonZero(propina, decimal(totalsNode, "serviceCharge", BigDecimal.ZERO));
        propina = firstNonZero(propina, decimal(rawPayload, "propina", BigDecimal.ZERO));
        propina = firstNonZero(propina, decimal(rawPayload, "totalPropina", BigDecimal.ZERO));

        JsonNode propinasNode = rawPayload.path("propinas");
        if (propinasNode.isArray()) {
            for (JsonNode charge : propinasNode) {
                propina = propina.add(firstDecimal(charge, "valor", firstDecimal(charge, "amount", BigDecimal.ZERO)));
            }
        }

        return money(propina);
    }

    private BigDecimal taxAmount(JsonNode tax, BigDecimal fallbackBase) {
        BigDecimal value = decimal(tax, "valor", BigDecimal.ZERO);
        if (value.compareTo(BigDecimal.ZERO) != 0) {
            return value;
        }

        BigDecimal percentage = decimal(tax, "porcentaje", BigDecimal.ZERO);
        if (percentage.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal base = decimal(tax, "baseImponible", fallbackBase);
        return money(base.multiply(percentage).divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP));
    }

    private JsonNode readTree(String value) {
        try {
            return value == null || value.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(value);
        } catch (Exception ex) {
            throw new InvoiceStorageException("JSON de factura inválido para reporte", ex);
        }
    }

    private BigDecimal firstDecimal(JsonNode node, String field, BigDecimal fallback) {
        BigDecimal value = decimal(node, field, null);
        return value != null ? value : fallback;
    }

    private BigDecimal decimal(JsonNode node, String field, BigDecimal fallback) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        return money(value.decimalValue());
    }

    private BigDecimal firstNonZero(BigDecimal value, BigDecimal fallback) {
        return value != null && value.compareTo(BigDecimal.ZERO) != 0 ? value : fallback;
    }

    private String addressText(JsonNode address) {
        if (address == null || address.isMissingNode() || address.isNull()) {
            return "";
        }
        if (address.isTextual()) {
            return address.asText();
        }
        return firstText(
                text(address, "direccionCompleta", ""),
                address.path("direccion_completa").asText(""),
                address.path("linea").asText("")
        );
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? fallback : value.asText();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(0, RoundingMode.HALF_UP);
    }

    public record AggregatedIncomeRow(
            String nit,
            String razonSocial,
            String direccion,
            BigDecimal ingresosGravados,
            BigDecimal ingresosExentos,
            BigDecimal iva,
            BigDecimal otrosImpuestos,
            BigDecimal totalIngresos
    ) {
    }

    public record CompanyInfo(String nit, String dv, String razonSocial) {
    }

    private record TaxSummary(
            BigDecimal iva,
            BigDecimal otrosImpuestos,
            BigDecimal baseGravada,
            BigDecimal baseExenta
    ) {
    }

    private record TotalsSummary(
            BigDecimal baseGravada,
            BigDecimal baseExenta,
            BigDecimal subtotal,
            BigDecimal total
    ) {
    }
}
