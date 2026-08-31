package com.zonak.portal.reports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zonak.portal.dto.EmissionRadianReportRow;
import com.zonak.portal.exception.InvoiceStorageException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class EmissionRadianReportRepository {
    private static final List<String> DEFAULT_KINDS = List.of("INVOICE", "CREDIT_NOTE", "DEBIT_NOTE");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EmissionRadianReportRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<EmissionRadianReportRow> findDocumentsWithRadianEvents(
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
                       COALESCE(NULLIF(i.document_kind, ''), 'INVOICE') AS document_kind,
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
        sql.append(" ORDER BY i.created_at DESC LIMIT 1000");

        List<DocumentRow> documents = jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> mapDocumentRow(rs),
                params.toArray()
        );

        Map<String, List<EmissionRadianReportRow.RadianEventSummary>> eventsByCufe = loadRadianEventsByCufe(tenantId);
        Map<String, List<EmissionRadianReportRow.RadianEventSummary>> eventsByInvoiceNumber =
                loadRadianEventsByInvoiceNumber(tenantId);

        List<EmissionRadianReportRow> rows = new ArrayList<>();
        for (DocumentRow document : documents) {
            rows.add(toReportRow(document, eventsByCufe, eventsByInvoiceNumber));
        }
        return rows;
    }

    private EmissionRadianReportRow toReportRow(
            DocumentRow document,
            Map<String, List<EmissionRadianReportRow.RadianEventSummary>> eventsByCufe,
            Map<String, List<EmissionRadianReportRow.RadianEventSummary>> eventsByInvoiceNumber
    ) {
        String documentNumber = document.prefijo() + document.numero();
        String referencedInvoice = text(document.rawPayload().path("factura_referencia"), "numeroDocumento", "");

        String facturaNumero = "";
        String ncNumero = "";
        String ndNumero = "";
        switch (document.documentKind()) {
            case "CREDIT_NOTE" -> {
                ncNumero = documentNumber;
                facturaNumero = referencedInvoice;
            }
            case "DEBIT_NOTE" -> {
                ndNumero = documentNumber;
                facturaNumero = referencedInvoice;
            }
            default -> facturaNumero = documentNumber;
        }

        List<EmissionRadianReportRow.RadianEventSummary> events = mergeEvents(
                eventsByCufe.get(normalizeKey(document.cufe())),
                eventsByInvoiceNumber.get(normalizeKey(documentNumber)),
                eventsByInvoiceNumber.get(normalizeKey(referencedInvoice)),
                parseEmbeddedEvents(document.dianResponse())
        );

        return new EmissionRadianReportRow(
                document.id(),
                document.documentKind(),
                labelForKind(document.documentKind()),
                facturaNumero,
                ncNumero,
                ndNumero,
                documentNumber,
                document.createdAt(),
                document.cufe(),
                resolveTotal(document.rawPayload(), document.totalsPayload()),
                document.estadoDian(),
                events
        );
    }

    private List<EmissionRadianReportRow.RadianEventSummary> mergeEvents(
            List<EmissionRadianReportRow.RadianEventSummary> byCufe,
            List<EmissionRadianReportRow.RadianEventSummary> byDocumentNumber,
            List<EmissionRadianReportRow.RadianEventSummary> byReferencedInvoice,
            List<EmissionRadianReportRow.RadianEventSummary> embedded
    ) {
        Map<String, EmissionRadianReportRow.RadianEventSummary> merged = new LinkedHashMap<>();
        appendEvents(merged, byCufe);
        appendEvents(merged, byDocumentNumber);
        appendEvents(merged, byReferencedInvoice);
        appendEvents(merged, embedded);
        return merged.values().stream()
                .sorted(Comparator.comparing(
                        EmissionRadianReportRow.RadianEventSummary::createdAt,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ))
                .toList();
    }

    private void appendEvents(
            Map<String, EmissionRadianReportRow.RadianEventSummary> merged,
            List<EmissionRadianReportRow.RadianEventSummary> events
    ) {
        if (events == null) {
            return;
        }
        for (EmissionRadianReportRow.RadianEventSummary event : events) {
            String key = event.eventCode() + "|" + event.eventLabel() + "|" + event.estado() + "|"
                    + (event.createdAt() == null ? "" : event.createdAt());
            merged.putIfAbsent(key, event);
        }
    }

    private Map<String, List<EmissionRadianReportRow.RadianEventSummary>> loadRadianEventsByCufe(UUID tenantId) {
        String sql = """
                SELECT event_code, event_label, estado, cufe, created_at
                FROM radian_events
                WHERE company_id = ?
                  AND btrim(cufe) <> ''
                ORDER BY created_at ASC
                """;
        return groupEvents(jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new EventGroupEntry(
                        rs.getString("cufe"),
                        new EmissionRadianReportRow.RadianEventSummary(
                                safe(rs.getString("event_code")),
                                safe(rs.getString("event_label")),
                                safe(rs.getString("estado")),
                                rs.getObject("created_at", OffsetDateTime.class)
                        )
                ),
                tenantId
        ));
    }

    private Map<String, List<EmissionRadianReportRow.RadianEventSummary>> loadRadianEventsByInvoiceNumber(
            UUID tenantId
    ) {
        String sql = """
                SELECT event_code, event_label, estado, invoice_number, created_at
                FROM radian_events
                WHERE company_id = ?
                  AND btrim(invoice_number) <> ''
                ORDER BY created_at ASC
                """;
        return groupEvents(jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new EventGroupEntry(
                        rs.getString("invoice_number"),
                        new EmissionRadianReportRow.RadianEventSummary(
                                safe(rs.getString("event_code")),
                                safe(rs.getString("event_label")),
                                safe(rs.getString("estado")),
                                rs.getObject("created_at", OffsetDateTime.class)
                        )
                ),
                tenantId
        ));
    }

    private Map<String, List<EmissionRadianReportRow.RadianEventSummary>> groupEvents(
            List<EventGroupEntry> entries
    ) {
        Map<String, List<EmissionRadianReportRow.RadianEventSummary>> grouped = new LinkedHashMap<>();
        for (EventGroupEntry entry : entries) {
            grouped.computeIfAbsent(normalizeKey(entry.groupKey()), ignored -> new ArrayList<>())
                    .add(entry.event());
        }
        return grouped;
    }

    private List<EmissionRadianReportRow.RadianEventSummary> parseEmbeddedEvents(JsonNode dianResponse) {
        JsonNode events = dianResponse.path("radian_events");
        if (!events.isArray()) {
            return List.of();
        }
        List<EmissionRadianReportRow.RadianEventSummary> parsed = new ArrayList<>();
        for (JsonNode event : events) {
            parsed.add(new EmissionRadianReportRow.RadianEventSummary(
                    text(event, "code", ""),
                    text(event, "label", text(event, "action", "Evento RADIAN")),
                    text(event, "estado", "REGISTRADO"),
                    parseOffset(text(event, "at", ""))
            ));
        }
        return parsed;
    }

    private DocumentRow mapDocumentRow(ResultSet rs) throws SQLException {
        JsonNode rawPayload = readTree(rs.getString("raw_payload"));
        JsonNode totalsPayload = readTree(rs.getString("totals_payload"));
        JsonNode dianResponse = readTree(rs.getString("dian_response"));
        String prefijo = safe(rs.getString("prefijo"));
        long numero = rs.getLong("numero");
        String cufe = firstText(
                safe(rs.getString("uuid_cude")),
                dianResponse.path("cufe").asText(""),
                dianResponse.path("cufeCune").asText(""),
                dianResponse.path("uuid").asText("")
        );
        return new DocumentRow(
                rs.getObject("id", UUID.class),
                safe(rs.getString("document_kind")),
                prefijo,
                numero,
                safe(rs.getString("estado_dian")),
                cufe,
                rs.getObject("created_at", OffsetDateTime.class),
                rawPayload,
                totalsPayload,
                dianResponse
        );
    }

    private BigDecimal resolveTotal(JsonNode rawPayload, JsonNode totalsPayload) {
        BigDecimal total = decimal(totalsPayload, "total", null);
        if (total == null) {
            total = decimal(rawPayload, "total", null);
        }
        if (total == null && totalsPayload.isObject()) {
            total = decimal(totalsPayload.path("totales"), "total", null);
        }
        return total == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : money(total);
    }

    private List<String> resolveKinds(String documentKind) {
        if (!StringUtils.hasText(documentKind)) {
            return DEFAULT_KINDS;
        }
        return switch (documentKind.trim().toUpperCase(Locale.ROOT)) {
            case "INVOICE", "FV", "FACTURA" -> List.of("INVOICE");
            case "CREDIT_NOTE", "NC", "NOTA_CREDITO" -> List.of("CREDIT_NOTE");
            case "DEBIT_NOTE", "ND", "NOTA_DEBITO" -> List.of("DEBIT_NOTE");
            default -> DEFAULT_KINDS;
        };
    }

    private static String labelForKind(String documentKind) {
        return switch (documentKind) {
            case "CREDIT_NOTE" -> "Nota crédito";
            case "DEBIT_NOTE" -> "Nota débito";
            default -> "Factura";
        };
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new InvoiceStorageException("JSON de reporte inválido", ex);
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null || node.isMissingNode()) {
            return fallback;
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? fallback : value.asText(fallback);
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal decimal(JsonNode node, String field, BigDecimal fallback) {
        if (node == null || node.isMissingNode()) {
            return fallback;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        try {
            return new BigDecimal(value.asText("0").trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static OffsetDateTime parseOffset(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private record DocumentRow(
            UUID id,
            String documentKind,
            String prefijo,
            long numero,
            String estadoDian,
            String cufe,
            OffsetDateTime createdAt,
            JsonNode rawPayload,
            JsonNode totalsPayload,
            JsonNode dianResponse
    ) {
    }

    private record EventGroupEntry(String groupKey, EmissionRadianReportRow.RadianEventSummary event) {
    }
}
