package com.zonak.portal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zonak.portal.dto.DianFiscalContext;
import com.zonak.portal.dto.InvoicePdfData;
import com.zonak.portal.exception.InvoiceStorageException;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

@Repository
public class InvoiceReportRepository {
    private static final String DIAN_QR_BASE_URL = "https://catalogo-vpfe.dian.gov.co/document/searchqr?documentkey=";
    private static final ZoneOffset COLOMBIA_OFFSET = ZoneOffset.ofHours(-5);
    private static final DateTimeFormatter ISSUE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ssXXX");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public InvoiceReportRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<InvoicePdfData> findApprovedInvoice(UUID tenantId, UUID invoiceId) {
        return findInvoice(tenantId, invoiceId, true);
    }

    public Optional<InvoicePdfData> findInvoice(UUID tenantId, UUID invoiceId) {
        return findInvoice(tenantId, invoiceId, false);
    }

    private Optional<InvoicePdfData> findInvoice(UUID tenantId, UUID invoiceId, boolean requireCufe) {
        String cufeFilter = requireCufe ? " AND i.uuid_cude IS NOT NULL" : "";
        List<InvoicePdfData> invoices = jdbcTemplate.query(
                """
                        SELECT i.id,
                               i.company_id,
                               i.prefijo,
                               i.numero,
                               i.estado_dian,
                               i.uuid_cude,
                               i.raw_dian_payload_jsonb::text AS raw_payload,
                               i.dian_response_jsonb::text AS dian_response,
                               c.razon_social,
                               c.nit,
                               c.dv,
                               COALESCE(c.email::text, '') AS company_email,
                               COALESCE(c.telefono, '') AS company_phone,
                               c.direccion::text AS company_address,
                               ep.resolucion_dian,
                               ep.rango_desde,
                               ep.rango_hasta,
                               ep.vigencia_desde,
                               ep.vigencia_hasta
                        FROM invoices i
                        JOIN companies c ON c.id = i.company_id
                        LEFT JOIN emission_points ep ON ep.id = i.emission_point_id
                        WHERE i.company_id = ?
                          AND i.id = ?
                        """ + cufeFilter,
                (rs, rowNum) -> mapInvoice(rs),
                tenantId,
                invoiceId
        );
        return invoices.stream().findFirst();
    }

    public Optional<String> findPdfS3Url(UUID tenantId, UUID invoiceId) {
        List<String> urls = jdbcTemplate.query(
                """
                        SELECT pdf_s3_url
                        FROM invoices
                        WHERE company_id = ?
                          AND id = ?
                          AND pdf_s3_url LIKE 's3://%'
                        """,
                (rs, rowNum) -> rs.getString("pdf_s3_url"),
                tenantId,
                invoiceId
        );
        return urls.stream().findFirst();
    }

    public void updatePdfUrl(UUID tenantId, UUID invoiceId, String pdfUrl) {
        jdbcTemplate.update(
                """
                        UPDATE invoices
                        SET pdf_s3_url = ?,
                            updated_at = now()
                        WHERE company_id = ?
                          AND id = ?
                        """,
                pdfUrl,
                tenantId,
                invoiceId
        );
    }

    private InvoicePdfData mapInvoice(ResultSet rs) throws SQLException {
        JsonNode rawPayload = readTree(rs.getString("raw_payload"));
        JsonNode dianResponse = readTree(rs.getString("dian_response"));
        SignedXmlMetadata signedXml = signedXmlMetadata(dianResponse.path("signedXmlBase64").asText(""));

        String prefijo = rs.getString("prefijo");
        long numero = rs.getLong("numero");
        String documentNumber = prefijo + numero;
        InvoicePdfData.Company company = new InvoicePdfData.Company(
                rs.getString("razon_social"),
                rs.getString("nit"),
                rs.getString("dv"),
                "R-99-PN",
                addressText(readTree(rs.getString("company_address"))),
                rs.getString("company_email"),
                rs.getString("company_phone"),
                ""
        );
        InvoicePdfData.Customer customer = mapCustomer(rawPayload.path("cliente"));
        List<InvoicePdfData.Item> items = mapItems(rawPayload.path("items"));
        List<InvoicePdfData.TaxDetail> impuestos = taxDetails(rawPayload.path("items"), false);
        List<InvoicePdfData.TaxDetail> retenciones = taxDetails(rawPayload.path("items"), true);
        List<InvoicePdfData.ChargeDetail> recargos = chargeDetails(rawPayload);
        InvoicePdfData.Totals totals = resolveTotals(rawPayload, items, impuestos, retenciones, recargos);
        DianFiscalContext fiscalContext = buildFiscalContext(
                documentNumber,
                rawPayload,
                signedXml,
                company,
                customer,
                totals,
                firstText(rs.getString("uuid_cude"), dianResponse.path("cufe"), dianResponse.path("cufeCune"), dianResponse.path("uuid"))
        );

        return new InvoicePdfData(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                documentNumber,
                prefijo,
                numero,
                fiscalContext,
                company,
                customer,
                new InvoicePdfData.Resolution(
                        safe(rs.getString("resolucion_dian")),
                        prefijo,
                        rs.getLong("rango_desde"),
                        rs.getLong("rango_hasta"),
                        rs.getObject("vigencia_desde", LocalDate.class),
                        rs.getObject("vigencia_hasta", LocalDate.class)
                ),
                items,
                impuestos,
                retenciones,
                recargos,
                totals,
                rs.getString("estado_dian")
        );
    }

    private DianFiscalContext buildFiscalContext(
            String documentNumber,
            JsonNode rawPayload,
            SignedXmlMetadata signedXml,
            InvoicePdfData.Company company,
            InvoicePdfData.Customer customer,
            InvoicePdfData.Totals totals,
            String persistedUniqueCode
    ) {
        DianFiscalContext.DocumentKind kind = signedXml.documentKind() != null
                ? signedXml.documentKind()
                : inferDocumentKind(rawPayload);
        String uniqueCode = firstString(signedXml.uniqueCode(), persistedUniqueCode);
        String uniqueCodeLabel = kind == DianFiscalContext.DocumentKind.CREDIT_NOTE ? "CUDE" : "CUFE";
        String title = kind == DianFiscalContext.DocumentKind.CREDIT_NOTE
                ? "Nota Crédito Electrónica"
                : "Factura Electrónica de Venta";
        String templateName = kind == DianFiscalContext.DocumentKind.CREDIT_NOTE
                ? "reports/nota-credito-template"
                : "reports/factura-template";
        LocalDate issueDate = signedXml.issueDate() != null
                ? signedXml.issueDate()
                : fallbackIssueDate(rawPayload);
        OffsetTime issueTime = signedXml.issueTime() != null
                ? signedXml.issueTime()
                : fallbackIssueTime(rawPayload);
        String qrUrl = dianQrUrl(uniqueCode);

        return new DianFiscalContext(
                kind,
                title,
                templateName,
                uniqueCodeLabel,
                firstString(signedXml.uniqueCodeSchemeName(), uniqueCodeLabel + "-SHA384"),
                uniqueCode,
                issueDate,
                issueTime,
                totals.iva(),
                totals.otrosImpuestos(),
                qrUrl,
                qrContent(documentNumber, issueDate, issueTime, company, customer, totals, uniqueCodeLabel, uniqueCode)
        );
    }

    private List<InvoicePdfData.Item> mapItems(JsonNode itemsNode) {
        List<InvoicePdfData.Item> items = new ArrayList<>();
        if (!itemsNode.isArray()) {
            return items;
        }

        for (JsonNode node : itemsNode) {
            BigDecimal cantidad = decimal(node, "cantidad", BigDecimal.ONE);
            BigDecimal valorUnitario = decimal(node, "precio_unitario", BigDecimal.ZERO);
            BigDecimal descuento = decimal(node, "descuento", BigDecimal.ZERO);
            BigDecimal subtotal = money(cantidad.multiply(valorUnitario).subtract(descuento));
            TaxBreakdown taxes = taxBreakdown(node.path("impuestos"), subtotal);
            items.add(new InvoicePdfData.Item(
                    cantidad,
                    text(node, "descripcion", "Item facturado"),
                    valorUnitario,
                    descuento,
                    taxes.iva(),
                    taxes.otrosImpuestos(),
                    subtotal.add(taxes.iva()).add(taxes.otrosImpuestos())
            ));
        }
        return items;
    }

    private InvoicePdfData.Totals resolveTotals(
            JsonNode rawPayload,
            List<InvoicePdfData.Item> items,
            List<InvoicePdfData.TaxDetail> impuestos,
            List<InvoicePdfData.TaxDetail> retenciones,
            List<InvoicePdfData.ChargeDetail> recargos
    ) {
        JsonNode totalsNode = rawPayload.path("totals_jsonb");
        if (totalsNode.isMissingNode() || totalsNode.isNull()) {
            totalsNode = rawPayload.path("totales");
        }

        BigDecimal subtotal = firstDecimal(totalsNode, "subtotal", sum(items, "subtotal"));
        BigDecimal descuentos = firstDecimal(totalsNode, "totalDescuentos", sum(items, "descuento"));
        BigDecimal iva = sumTaxDetails(impuestos, "01");
        BigDecimal totalImpuestos = sumTaxDetails(impuestos);
        BigDecimal otrosImpuestos = totalImpuestos.subtract(iva);
        BigDecimal totalRetenciones = sumTaxDetails(retenciones);
        BigDecimal totalRecargos = sumChargeDetails(recargos);
        BigDecimal baseNeta = subtotal.subtract(descuentos);
        BigDecimal total = baseNeta.add(totalImpuestos).subtract(totalRetenciones).add(totalRecargos);
        return new InvoicePdfData.Totals(
                money(subtotal),
                money(descuentos),
                money(iva),
                money(otrosImpuestos),
                money(totalImpuestos),
                money(totalRetenciones),
                money(totalRecargos),
                money(total)
        );
    }

    private List<InvoicePdfData.TaxDetail> taxDetails(JsonNode itemsNode, boolean retentions) {
        Map<String, TaxAccumulator> details = new LinkedHashMap<>();
        if (!itemsNode.isArray()) {
            return List.of();
        }

        for (JsonNode item : itemsNode) {
            BigDecimal cantidad = decimal(item, "cantidad", BigDecimal.ONE);
            BigDecimal valorUnitario = decimal(item, "precio_unitario", BigDecimal.ZERO);
            BigDecimal descuento = decimal(item, "descuento", BigDecimal.ZERO);
            BigDecimal lineBase = money(cantidad.multiply(valorUnitario).subtract(descuento));
            JsonNode taxesNode = item.path("impuestos");
            if (!taxesNode.isArray()) {
                continue;
            }
            for (JsonNode tax : taxesNode) {
                if (tax.path("esRetencion").asBoolean(false) != retentions) {
                    continue;
                }
                BigDecimal percentage = decimal(tax, "porcentaje", BigDecimal.ZERO);
                BigDecimal base = decimal(tax, "baseImponible", lineBase);
                BigDecimal value = taxAmount(tax, lineBase);
                String code = text(tax, "codigo", "");
                String name = firstString(text(tax, "nombre", ""), code.isBlank() ? "" : "Impuesto " + code);
                String key = code + "|" + name + "|" + percentage.toPlainString();
                details.computeIfAbsent(key, ignored -> new TaxAccumulator(code, name, percentage))
                        .add(base, value);
            }
        }

        return details.values().stream()
                .map(TaxAccumulator::toTaxDetail)
                .toList();
    }

    private List<InvoicePdfData.ChargeDetail> chargeDetails(JsonNode rawPayload) {
        List<InvoicePdfData.ChargeDetail> details = new ArrayList<>();
        appendChargeDetails(details, rawPayload.path("recargos"));
        appendChargeDetails(details, rawPayload.path("cargos"));
        appendChargeDetails(details, rawPayload.path("charges"));
        appendChargeDetails(details, rawPayload.path("chargeDetails"));
        appendChargeDetails(details, rawPayload.path("propinas"));

        JsonNode totalsNode = rawPayload.path("totals_jsonb");
        if (totalsNode.isMissingNode() || totalsNode.isNull()) {
            totalsNode = rawPayload.path("totales");
        }
        appendChargeDetails(details, totalsNode.path("recargos"));
        appendChargeDetails(details, totalsNode.path("cargos"));
        appendChargeDetails(details, totalsNode.path("charges"));

        if (details.isEmpty()) {
            BigDecimal propina = firstDecimal(
                    totalsNode,
                    "propina",
                    firstDecimal(totalsNode, "totalPropina", firstDecimal(rawPayload, "propina", BigDecimal.ZERO))
            );
            propina = firstNonZero(propina, decimal(totalsNode, "valorPropina", BigDecimal.ZERO));
            propina = firstNonZero(propina, decimal(totalsNode, "tip", BigDecimal.ZERO));
            propina = firstNonZero(propina, decimal(totalsNode, "serviceCharge", BigDecimal.ZERO));
            propina = firstNonZero(propina, decimal(rawPayload, "totalPropina", BigDecimal.ZERO));
            if (propina.compareTo(BigDecimal.ZERO) > 0) {
                details.add(new InvoicePdfData.ChargeDetail("PROPINA", "Propina", money(propina)));
            }
        }
        return details;
    }

    private void appendChargeDetails(List<InvoicePdfData.ChargeDetail> details, JsonNode chargesNode) {
        if (!chargesNode.isArray()) {
            return;
        }
        for (JsonNode charge : chargesNode) {
            BigDecimal value = firstDecimal(
                    charge,
                    "valor",
                    firstDecimal(charge, "amount", firstDecimal(charge, "total", BigDecimal.ZERO))
            );
            value = firstNonZero(value, decimal(charge, "importe", BigDecimal.ZERO));
            if (value.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            String name = firstString(
                    text(charge, "nombre", ""),
                    text(charge, "descripcion", ""),
                    text(charge, "reason", ""),
                    "Recargo"
            );
            String code = firstString(text(charge, "codigo", ""), text(charge, "code", ""), normalizedChargeCode(name));
            details.add(new InvoicePdfData.ChargeDetail(code, name, money(value)));
        }
    }

    private BigDecimal firstNonZero(BigDecimal value, BigDecimal fallback) {
        return value != null && value.compareTo(BigDecimal.ZERO) != 0 ? value : fallback;
    }

    private String normalizedChargeCode(String name) {
        return name != null && name.toLowerCase().contains("propina") ? "PROPINA" : "RECARGO";
    }

    private BigDecimal sumTaxDetails(List<InvoicePdfData.TaxDetail> details) {
        return money(details.stream()
                .map(InvoicePdfData.TaxDetail::valor)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private BigDecimal sumTaxDetails(List<InvoicePdfData.TaxDetail> details, String code) {
        return money(details.stream()
                .filter(detail -> code.equals(detail.codigo()))
                .map(InvoicePdfData.TaxDetail::valor)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private BigDecimal sumChargeDetails(List<InvoicePdfData.ChargeDetail> details) {
        return money(details.stream()
                .map(InvoicePdfData.ChargeDetail::valor)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private TaxBreakdown taxBreakdown(JsonNode taxesNode, BigDecimal lineBase) {
        if (!taxesNode.isArray()) {
            return new TaxBreakdown(BigDecimal.ZERO, BigDecimal.ZERO);
        }

        BigDecimal iva = BigDecimal.ZERO;
        BigDecimal otros = BigDecimal.ZERO;
        for (JsonNode tax : taxesNode) {
            if (tax.path("esRetencion").asBoolean(false)) {
                continue;
            }
            BigDecimal value = taxAmount(tax, lineBase);
            if ("01".equals(text(tax, "codigo", ""))) {
                iva = iva.add(value);
            } else {
                otros = otros.add(value);
            }
        }
        return new TaxBreakdown(money(iva), money(otros));
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

    private String qrContent(
            String documentNumber,
            LocalDate issueDate,
            OffsetTime issueTime,
            InvoicePdfData.Company company,
            InvoicePdfData.Customer customer,
            InvoicePdfData.Totals totals,
            String uniqueCodeLabel,
            String uniqueCode
    ) {
        return String.join("\n",
                "NumFac: " + documentNumber,
                "FecFac: " + issueDate,
                "HorFac: " + issueTime.format(ISSUE_TIME_FORMATTER),
                "NitFac: " + company.nit(),
                "DocAdq: " + customer.identificacion(),
                "ValFac: " + plain(totals.subtotal()),
                "ValIva: " + plain(totals.iva()),
                "ValOtroIm: " + plain(totals.otrosImpuestos()),
                "ValTotal: " + plain(totals.total()),
                uniqueCodeLabel + ": " + uniqueCode,
                dianQrUrl(uniqueCode)
        );
    }

    private String dianQrUrl(String uniqueCode) {
        return DIAN_QR_BASE_URL + uniqueCode;
    }

    private SignedXmlMetadata signedXmlMetadata(String signedXmlBase64) {
        if (signedXmlBase64 == null || signedXmlBase64.isBlank()) {
            return SignedXmlMetadata.empty();
        }

        try {
            byte[] xmlBytes = Base64.getDecoder().decode(signedXmlBase64);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xmlBytes));
            Element root = document.getDocumentElement();
            DianFiscalContext.DocumentKind kind = "CreditNote".equals(root.getLocalName())
                    ? DianFiscalContext.DocumentKind.CREDIT_NOTE
                    : DianFiscalContext.DocumentKind.INVOICE;
            Element uuid = firstElement(document, "UUID");
            return new SignedXmlMetadata(
                    kind,
                    localDateText(firstText("", firstElement(document, "IssueDate"))),
                    offsetTimeText(firstText("", firstElement(document, "IssueTime"))),
                    uuid != null ? uuid.getTextContent() : "",
                    uuid != null ? uuid.getAttribute("schemeName") : ""
            );
        } catch (Exception ex) {
            throw new InvoiceStorageException("XML firmado inválido para contexto fiscal DIAN", ex);
        }
    }

    private Element firstElement(Document document, String localName) {
        NodeList nodes = document.getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private LocalDate fallbackIssueDate(JsonNode rawPayload) {
        String rawDate = firstString(text(rawPayload, "fechaEmision", ""), text(rawPayload, "fecha_emision", ""));
        if (!rawDate.isBlank()) {
            return OffsetDateTime.parse(rawDate).toLocalDate();
        }
        throw new InvoiceStorageException("IssueDate fiscal no disponible para representación gráfica");
    }

    private OffsetTime fallbackIssueTime(JsonNode rawPayload) {
        String rawDate = firstString(text(rawPayload, "fechaEmision", ""), text(rawPayload, "fecha_emision", ""));
        if (!rawDate.isBlank()) {
            return OffsetDateTime.parse(rawDate).toOffsetTime().withOffsetSameInstant(COLOMBIA_OFFSET);
        }
        throw new InvoiceStorageException("IssueTime fiscal no disponible para representación gráfica");
    }

    private DianFiscalContext.DocumentKind inferDocumentKind(JsonNode rawPayload) {
        return rawPayload.has("credit_note_type_code") || rawPayload.has("factura_referencia")
                ? DianFiscalContext.DocumentKind.CREDIT_NOTE
                : DianFiscalContext.DocumentKind.INVOICE;
    }

    private InvoicePdfData.Customer mapCustomer(JsonNode customerNode) {
        return new InvoicePdfData.Customer(
                text(customerNode, "razon_social", "Cliente"),
                firstText(text(customerNode, "numero_identificacion", ""), customerNode.path("numeroIdentificacion")),
                addressText(customerNode.path("direccion")),
                text(customerNode, "email", "")
        );
    }

    private JsonNode readTree(String value) {
        try {
            return value == null || value.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(value);
        } catch (Exception ex) {
            throw new InvoiceStorageException("JSON de factura inválido para representación gráfica", ex);
        }
    }

    private BigDecimal sum(List<InvoicePdfData.Item> items, String field) {
        BigDecimal total = BigDecimal.ZERO;
        for (InvoicePdfData.Item item : items) {
            total = switch (field) {
                case "subtotal" -> total.add(item.cantidad().multiply(item.valorUnitario()));
                case "descuento" -> total.add(item.descuento());
                case "iva" -> total.add(item.iva());
                case "otrosImpuestos" -> total.add(item.otrosImpuestos());
                default -> total;
            };
        }
        return money(total);
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

    private String addressText(JsonNode address) {
        if (address == null || address.isMissingNode() || address.isNull()) {
            return "";
        }
        if (address.isTextual()) {
            return address.asText();
        }
        return firstText(
                text(address, "direccionCompleta", ""),
                address.path("direccion_completa"),
                address.path("linea")
        );
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? fallback : value.asText();
    }

    private String firstText(String fallback, JsonNode... nodes) {
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull() && !node.asText().isBlank()) {
                return node.asText();
            }
        }
        return "";
    }

    private String firstText(String fallback, Element element) {
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return element == null ? "" : element.getTextContent();
    }

    private String firstString(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private LocalDate localDateText(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    private OffsetTime offsetTimeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.matches(".*[+-]\\d{2}:\\d{2}$") ? value : value + "-05:00";
        return OffsetTime.parse(normalized).withOffsetSameInstant(COLOMBIA_OFFSET);
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String plain(BigDecimal value) {
        return money(value).toPlainString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record SignedXmlMetadata(
            DianFiscalContext.DocumentKind documentKind,
            LocalDate issueDate,
            OffsetTime issueTime,
            String uniqueCode,
            String uniqueCodeSchemeName
    ) {
        static SignedXmlMetadata empty() {
            return new SignedXmlMetadata(null, null, null, "", "");
        }
    }

    private record TaxBreakdown(BigDecimal iva, BigDecimal otrosImpuestos) {
    }

    private static final class TaxAccumulator {
        private final String code;
        private final String name;
        private final BigDecimal percentage;
        private BigDecimal base = BigDecimal.ZERO;
        private BigDecimal value = BigDecimal.ZERO;

        private TaxAccumulator(String code, String name, BigDecimal percentage) {
            this.code = code;
            this.name = name;
            this.percentage = percentage;
        }

        private TaxAccumulator add(BigDecimal base, BigDecimal value) {
            this.base = this.base.add(base);
            this.value = this.value.add(value);
            return this;
        }

        private InvoicePdfData.TaxDetail toTaxDetail() {
            return new InvoicePdfData.TaxDetail(code, name, percentage, base, value);
        }
    }
}
