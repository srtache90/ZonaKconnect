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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private final Boolean auditTableExists;

    public ReceivedInvoiceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.auditTableExists = detectAuditTable();
    }

    public List<ReceivedInvoiceRow> findBySociedad(UUID sociedadId) {
        return findReceived(ReceivedInvoiceFilter.ofSociedad(sociedadId));
    }

    public List<ReceivedInvoiceRow> findReceived(
            UUID sociedadId,
            LocalDate fromDate,
            LocalDate toDate,
            String estadoDian
    ) {
        return findReceived(new ReceivedInvoiceFilter(
                sociedadId, fromDate, toDate, estadoDian, null, null, null, null, null, null, true, null
        ));
    }

    public List<ReceivedInvoiceRow> findReceived(ReceivedInvoiceFilter filter) {
        if (filter == null || filter.sociedadId() == null) {
            return List.of();
        }
        String sociedadNit = loadSociedadNit(filter.sociedadId());
        StringBuilder sql = new StringBuilder("""
                SELECT r.id,
                       r.supplier_name,
                       r.supplier_nit,
                       r.invoice_number,
                       r.cufe,
                       r.issue_date,
                       r.total_amount,
                       r.estado_dian,
                       r.pdf_s3_url,
                       r.xml_s3_url,
                       r.created_at,
                       r.updated_at,
                       r.raw_payload_jsonb::text AS raw_payload,
                       r.dian_response_jsonb::text AS dian_response,
                       r.source,
                       r.assigned_emission_point_id,
                       COALESCE(ep.codigo || ' - ' || ep.nombre, '') AS assigned_point_label,
                       COALESCE(r.assignment_source, 'UNASSIGNED') AS assignment_source
                FROM received_invoices r
                LEFT JOIN emission_points ep ON ep.id = r.assigned_emission_point_id
                WHERE r.company_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(filter.sociedadId());

        if (filter.fromDate() != null) {
            sql.append(" AND r.created_at::date >= ?");
            params.add(filter.fromDate());
        }
        if (filter.toDate() != null) {
            sql.append(" AND r.created_at::date <= ?");
            params.add(filter.toDate());
        }
        if (StringUtils.hasText(filter.estadoDian())) {
            sql.append(" AND UPPER(r.estado_dian) = ?");
            params.add(filter.estadoDian().trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(filter.proveedor())) {
            sql.append(" AND (r.supplier_name ILIKE ? OR r.supplier_nit ILIKE ?)");
            String like = "%" + filter.proveedor().trim() + "%";
            params.add(like);
            params.add(like);
        }
        if (StringUtils.hasText(filter.cufe())) {
            sql.append(" AND COALESCE(r.cufe, '') ILIKE ?");
            params.add("%" + filter.cufe().trim() + "%");
        }
        if (filter.minTotal() != null) {
            sql.append(" AND r.total_amount >= ?");
            params.add(filter.minTotal());
        }
        if (filter.maxTotal() != null) {
            sql.append(" AND r.total_amount <= ?");
            params.add(filter.maxTotal());
        }
        if (Boolean.TRUE.equals(filter.openOnly())) {
            sql.append("""
                     AND UPPER(COALESCE(r.estado_dian, 'PENDIENTE')) NOT IN (
                        'ACEPTADA_087', 'ACEPTADA_TACITA', 'RECHAZADA_088', '087', '088', 'RECHAZADO'
                     )
                    """);
        }
        if (filter.assignedEmissionPointId() != null) {
            sql.append(" AND r.assigned_emission_point_id = ?");
            params.add(filter.assignedEmissionPointId());
        } else if (filter.allowedEmissionPointIds() != null) {
            List<UUID> allowed = filter.allowedEmissionPointIds();
            boolean includeUnassigned = !Boolean.FALSE.equals(filter.includeUnassigned());
            if (allowed.isEmpty() && !includeUnassigned) {
                sql.append(" AND 1=0");
            } else if (!allowed.isEmpty()) {
                String placeholders = String.join(",", java.util.Collections.nCopies(allowed.size(), "?"));
                if (includeUnassigned) {
                    sql.append(" AND (r.assigned_emission_point_id IS NULL OR r.assigned_emission_point_id IN (")
                            .append(placeholders).append("))");
                } else {
                    sql.append(" AND r.assigned_emission_point_id IN (").append(placeholders).append(")");
                }
                params.addAll(allowed);
            } else if (includeUnassigned) {
                sql.append(" AND r.assigned_emission_point_id IS NULL");
            }
        }
        sql.append(" ORDER BY r.created_at DESC LIMIT 500");

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapRow(rs, sociedadNit), params.toArray());
    }

    public Optional<ReceivedInvoiceDetail> findDetail(UUID sociedadId, UUID invoiceId) {
        String sociedadNit = loadSociedadNit(sociedadId);
        List<ReceivedInvoiceDetail> rows = jdbcTemplate.query(
                """
                        SELECT r.id,
                               r.supplier_name,
                               r.supplier_nit,
                               r.invoice_number,
                               r.cufe,
                               r.issue_date,
                               r.total_amount,
                               r.estado_dian,
                               r.pdf_s3_url,
                               r.xml_s3_url,
                               r.created_at,
                               r.updated_at,
                               r.raw_payload_jsonb::text AS raw_payload,
                               r.dian_response_jsonb::text AS dian_response,
                               r.source,
                               r.assigned_emission_point_id,
                               COALESCE(ep.codigo || ' - ' || ep.nombre, '') AS assigned_point_label,
                               COALESCE(r.assignment_source, 'UNASSIGNED') AS assignment_source
                        FROM received_invoices r
                        LEFT JOIN emission_points ep ON ep.id = r.assigned_emission_point_id
                        WHERE r.company_id = ? AND r.id = ?
                        """,
                (rs, rowNum) -> mapDetail(rs, sociedadId, sociedadNit),
                sociedadId,
                invoiceId
        );
        return rows.stream().findFirst();
    }

    public Optional<UUID> findDuplicateByCufe(UUID sociedadId, String cufe, UUID excludeId) {
        if (!StringUtils.hasText(cufe)) {
            return Optional.empty();
        }
        List<UUID> ids = jdbcTemplate.query(
                """
                        SELECT id
                        FROM received_invoices
                        WHERE company_id = ?
                          AND cufe = ?
                          AND (?::uuid IS NULL OR id <> ?)
                        LIMIT 1
                        """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                sociedadId,
                cufe,
                excludeId,
                excludeId
        );
        return ids.stream().findFirst();
    }

    public List<ReceivedInvoiceRow> findEligibleForTacitAcceptance(UUID sociedadId) {
        return findReceived(new ReceivedInvoiceFilter(
                sociedadId, null, null, "RECIBIDA_086", null, null, null, null, null, null, true, null
        ));
    }

    public int markTacitAcceptance(UUID sociedadId, UUID invoiceId, String timelineJsonPatch) {
        return jdbcTemplate.update(
                """
                        UPDATE received_invoices
                        SET estado_dian = 'ACEPTADA_TACITA',
                            dian_response_jsonb = COALESCE(dian_response_jsonb, '{}'::jsonb) || ?::jsonb,
                            updated_at = now()
                        WHERE id = ?
                          AND company_id = ?
                          AND UPPER(estado_dian) IN ('RECIBIDA_086', '086')
                        """,
                timelineJsonPatch,
                invoiceId,
                sociedadId
        );
    }

    public Optional<String> findXmlBase(UUID sociedadId, UUID invoiceId) {
        List<String> xmls = jdbcTemplate.query(
                """
                        SELECT COALESCE(r.raw_payload_jsonb->>'xml_base', '') AS xml_content
                        FROM received_invoices r
                        WHERE r.company_id = ? AND r.id = ?
                        """,
                (rs, rowNum) -> rs.getString("xml_content"),
                sociedadId,
                invoiceId
        );
        return xmls.stream().filter(StringUtils::hasText).findFirst();
    }

    public Optional<UUID> findOwnedCompanyId(UUID invoiceId, List<UUID> sociedadIds) {
        if (invoiceId == null || sociedadIds == null || sociedadIds.isEmpty()) {
            return Optional.empty();
        }
        String placeholders = sociedadIds.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("?");
        List<Object> params = new ArrayList<>();
        params.add(invoiceId);
        params.addAll(sociedadIds);
        List<UUID> ids = jdbcTemplate.query(
                """
                        SELECT company_id
                        FROM received_invoices
                        WHERE id = ?
                          AND company_id IN (""" + placeholders + ")",
                (rs, rowNum) -> rs.getObject("company_id", UUID.class),
                params.toArray()
        );
        return ids.stream().findFirst();
    }

    public Optional<byte[]> findPdfBase(UUID sociedadId, UUID invoiceId) {
        List<String> encoded = jdbcTemplate.query(
                """
                        SELECT COALESCE(r.raw_payload_jsonb->>'pdf_base', '') AS pdf_content
                        FROM received_invoices r
                        WHERE r.company_id = ? AND r.id = ?
                        """,
                (rs, rowNum) -> rs.getString("pdf_content"),
                sociedadId,
                invoiceId
        );
        return encoded.stream()
                .filter(StringUtils::hasText)
                .map(value -> {
                    try {
                        return java.util.Base64.getDecoder().decode(value);
                    } catch (IllegalArgumentException ex) {
                        return new byte[0];
                    }
                })
                .filter(bytes -> bytes.length > 4)
                .findFirst();
    }

    public String loadSociedadNit(UUID sociedadId) {
        try {
            List<String> nits = jdbcTemplate.query(
                    """
                            SELECT COALESCE(NULLIF(s.nit, ''), NULLIF(c.nit, ''), '') AS nit
                            FROM (SELECT ?::uuid AS id) x
                            LEFT JOIN sociedades s ON s.id = x.id
                            LEFT JOIN companies c ON c.id = x.id
                            """,
                    (rs, rowNum) -> rs.getString("nit"),
                    sociedadId
            );
            return nits.stream().filter(StringUtils::hasText).findFirst().orElse("");
        } catch (Exception ex) {
            return "";
        }
    }

    private ReceivedInvoiceRow mapRow(ResultSet rs, String sociedadNit) throws SQLException {
        JsonNode rawPayload = readTree(rs.getString("raw_payload"));
        JsonNode dianResponse = readTree(rs.getString("dian_response"));
        OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
        String pdfS3Url = safe(rs.getString("pdf_s3_url"));
        String xmlS3Url = safe(rs.getString("xml_s3_url"));
        String xmlBase = text(rawPayload, "xml_base", "");
        boolean documentsAvailable = !pdfS3Url.isBlank() || !xmlS3Url.isBlank() || !xmlBase.isBlank();
        String cufe = firstText(safe(rs.getString("cufe")), text(rawPayload, "cufe", ""));
        LocalDate issueDate = rs.getObject("issue_date", LocalDate.class);
        String fecha = issueDate != null ? issueDate.format(DATE_FORMAT) : text(rawPayload, "fecha_emision", "");
        if (fecha.isBlank() && createdAt != null) {
            fecha = createdAt.toLocalDate().format(DATE_FORMAT);
        }
        RecepcionEstadoDian estado = RecepcionEstadoDian.fromDb(rs.getString("estado_dian"));
        List<String> issues = buildValidationIssues(cufe, rawPayload, sociedadNit);
        PlazoInfo plazo = resolvePlazo(estado, dianResponse, createdAt);
        BigDecimal total = rs.getBigDecimal("total_amount");
        if (total == null) {
            total = BigDecimal.ZERO;
        }

        return new ReceivedInvoiceRow(
                rs.getObject("id", UUID.class),
                firstText(rs.getString("supplier_name"), "Proveedor"),
                firstText(rs.getString("supplier_nit"), "—"),
                firstText(rs.getString("invoice_number"), "SIN-NUMERO"),
                cufe,
                total.setScale(2, RoundingMode.HALF_UP),
                fecha,
                estado,
                pdfS3Url,
                xmlS3Url,
                documentsAvailable,
                createdAt,
                issues,
                RecepcionCufeValidator.isStructurallyValid(cufe),
                plazo.status(),
                plazo.label(),
                plazo.diasRestantes(),
                plazo.limite(),
                rs.getObject("assigned_emission_point_id", UUID.class),
                safe(rs.getString("assigned_point_label")),
                safe(rs.getString("assignment_source"))
        );
    }

    private ReceivedInvoiceDetail mapDetail(ResultSet rs, UUID sociedadId, String sociedadNit) throws SQLException {
        ReceivedInvoiceRow row = mapRow(rs, sociedadNit);
        JsonNode rawPayload = readTree(rs.getString("raw_payload"));
        JsonNode dianResponse = readTree(rs.getString("dian_response"));
        OffsetDateTime updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
        String receptorNit = firstText(
                text(rawPayload, "receptor_nit", ""),
                nestedText(rawPayload, "receptor", "nit")
        );
        boolean receptorMatch = !StringUtils.hasText(sociedadNit)
                || !StringUtils.hasText(receptorNit)
                || RecepcionCufeValidator.sameNit(sociedadNit, receptorNit);
        List<RecepcionEventTimelineItem> timeline = buildTimeline(
                sociedadId, row.id(), rawPayload, dianResponse, row.createdAt(), safe(rs.getString("source"))
        );
        LocalDate recibo086 = parseDate(text(dianResponse, "recibo_086_at", ""));
        String xml = text(rawPayload, "xml_base", "");
        String preview = xml.length() > 4000 ? xml.substring(0, 4000) + "…" : xml;
        PlazoInfo plazo = resolvePlazo(row.estadoDian(), dianResponse, row.createdAt());

        return new ReceivedInvoiceDetail(
                row.id(),
                row.proveedorName(),
                row.proveedorNit(),
                receptorNit,
                sociedadNit,
                row.invoiceNumber(),
                row.cufe(),
                row.totalAmount(),
                row.fechaEmision(),
                row.estadoDian(),
                row.createdAt(),
                updatedAt,
                row.documentsAvailable(),
                row.validationIssues(),
                row.cufeValid(),
                receptorMatch,
                plazo.status(),
                plazo.label(),
                plazo.diasRestantes(),
                plazo.limite(),
                recibo086,
                timeline,
                firstText(rs.getString("source"), text(rawPayload, "source", "")),
                preview
        );
    }

    private List<RecepcionEventTimelineItem> buildTimeline(
            UUID sociedadId,
            UUID invoiceId,
            JsonNode rawPayload,
            JsonNode dianResponse,
            OffsetDateTime createdAt,
            String source
    ) {
        List<RecepcionEventTimelineItem> items = new ArrayList<>();
        items.add(new RecepcionEventTimelineItem(
                "RCV",
                "RECIBIDA",
                "Documento recibido en bandeja",
                createdAt,
                "",
                "",
                "Fuente: " + firstText(source, text(rawPayload, "source", ""), "N/D"),
                "import"
        ));

        JsonNode events = dianResponse.path("radian_events");
        if (events.isArray()) {
            for (JsonNode event : events) {
                items.add(new RecepcionEventTimelineItem(
                        text(event, "code", ""),
                        text(event, "action", ""),
                        text(event, "label", text(event, "action", "Evento RADIAN")),
                        parseOffset(text(event, "at", "")),
                        text(event, "trackId", ""),
                        text(event, "cude", ""),
                        text(event, "detail", ""),
                        "dian_response"
                ));
            }
        }

        if (Boolean.TRUE.equals(auditTableExists)) {
            try {
                List<RecepcionEventTimelineItem> auditItems = jdbcTemplate.query(
                        """
                                SELECT action, payload::text AS payload, created_at
                                FROM audit_events
                                WHERE company_id = ?
                                  AND entity_type IN ('received_invoice', 'invoice')
                                  AND entity_id = ?
                                ORDER BY created_at ASC
                                """,
                        (rs, rowNum) -> {
                            JsonNode payload = readTree(rs.getString("payload"));
                            String action = safe(rs.getString("action"));
                            return new RecepcionEventTimelineItem(
                                    extractCode(action),
                                    action,
                                    labelForAction(action),
                                    rs.getObject("created_at", OffsetDateTime.class),
                                    text(payload, "trackID", text(payload, "trackId", "")),
                                    text(payload, "cune", text(payload, "cufeCune", "")),
                                    compactJson(payload),
                                    "audit"
                            );
                        },
                        sociedadId,
                        invoiceId
                );
                items.addAll(auditItems);
            } catch (Exception ignored) {
                // timeline parcial
            }
        }
        items.sort((a, b) -> {
            if (a.at() == null && b.at() == null) return 0;
            if (a.at() == null) return 1;
            if (b.at() == null) return -1;
            return a.at().compareTo(b.at());
        });
        return items;
    }

    private List<String> buildValidationIssues(String cufe, JsonNode rawPayload, String sociedadNit) {
        List<String> issues = new ArrayList<>(RecepcionCufeValidator.validateCufe(cufe));
        String receptorNit = firstText(
                text(rawPayload, "receptor_nit", ""),
                nestedText(rawPayload, "receptor", "nit")
        );
        if (StringUtils.hasText(sociedadNit) && StringUtils.hasText(receptorNit)
                && !RecepcionCufeValidator.sameNit(sociedadNit, receptorNit)) {
            issues.add("NIT receptor del XML (" + receptorNit + ") no coincide con la sociedad (" + sociedadNit + ")");
        } else if (StringUtils.hasText(sociedadNit) && !StringUtils.hasText(receptorNit)) {
            issues.add("NIT receptor no identificado en el XML");
        }
        return issues;
    }

    PlazoInfo resolvePlazo(RecepcionEstadoDian estado, JsonNode dianResponse, OffsetDateTime createdAt) {
        if (estado != null && estado.isTerminal()) {
            return new PlazoInfo(RecepcionPlazoStatus.CERRADO, "Cerrado", null, null);
        }
        if (estado == null || !estado.awaitsTacitAcceptance()) {
            return new PlazoInfo(RecepcionPlazoStatus.NO_APLICA, "Plazo inicia tras 086", null, null);
        }
        LocalDate reciboDate = parseDate(text(dianResponse, "recibo_086_at", ""));
        if (reciboDate == null && createdAt != null) {
            reciboDate = createdAt.atZoneSameInstant(RecepcionBusinessDays.BOGOTA).toLocalDate();
        }
        if (reciboDate == null) {
            return new PlazoInfo(RecepcionPlazoStatus.NO_APLICA, "Sin fecha 086", null, null);
        }
        LocalDate limite = RecepcionBusinessDays.addBusinessDays(
                reciboDate, RecepcionBusinessDays.TACIT_ACCEPTANCE_BUSINESS_DAYS
        );
        LocalDate today = RecepcionBusinessDays.todayBogota();
        if (today.isAfter(limite)) {
            long overdue = RecepcionBusinessDays.businessDaysBetween(limite, today);
            return new PlazoInfo(
                    RecepcionPlazoStatus.VENCIDO,
                    "Vencido hace " + overdue + " día(s) hábil(es) → aceptación tácita",
                    -((int) overdue),
                    limite
            );
        }
        int remaining = RecepcionBusinessDays.businessDaysBetween(today, limite);
        if (remaining <= 1) {
            return new PlazoInfo(
                    RecepcionPlazoStatus.POR_VENCER,
                    "Vence " + limite + " (" + remaining + " día(s) hábil(es))",
                    remaining,
                    limite
            );
        }
        return new PlazoInfo(
                RecepcionPlazoStatus.EN_PLAZO,
                "Límite " + limite + " (" + remaining + " días hábiles)",
                remaining,
                limite
        );
    }

    private String extractCode(String action) {
        if (!StringUtils.hasText(action)) return "";
        if (action.contains("085")) return "085";
        if (action.contains("086")) return "086";
        if (action.contains("087")) return "087";
        if (action.contains("088")) return "088";
        if (action.toUpperCase(Locale.ROOT).contains("TACITA")) return "TAC";
        return "";
    }

    private String labelForAction(String action) {
        if (!StringUtils.hasText(action)) return "Evento";
        return switch (action.toUpperCase(Locale.ROOT)) {
            case "ACUSE_085" -> "Acuse de recibo 085";
            case "RECIBO_BIENES_086" -> "Recibo de bienes/servicios 086";
            case "ACEPTACION_087" -> "Aceptación expresa 087";
            case "RECHAZO_088" -> "Reclamo/rechazo 088";
            case "ACEPTACION_TACITA" -> "Aceptación tácita (3 días hábiles)";
            default -> action;
        };
    }

    private String compactJson(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isEmpty()) return "";
        String value = node.toString();
        return value.length() > 180 ? value.substring(0, 180) + "…" : value;
    }

    private OffsetDateTime parseOffset(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            try {
                LocalDate date = LocalDate.parse(value.substring(0, Math.min(10, value.length())));
                return date.atStartOfDay(RecepcionBusinessDays.BOGOTA).toOffsetDateTime();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private LocalDate parseDate(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            if (value.length() >= 10) return LocalDate.parse(value.substring(0, 10));
            return LocalDate.parse(value);
        } catch (Exception ex) {
            try {
                return OffsetDateTime.parse(value).atZoneSameInstant(RecepcionBusinessDays.BOGOTA).toLocalDate();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private Boolean detectAuditTable() {
        try {
            return jdbcTemplate.queryForObject(
                    """
                            SELECT EXISTS (
                                SELECT 1 FROM information_schema.tables
                                WHERE table_schema = 'public' AND table_name = 'audit_events'
                            )
                            """,
                    Boolean.class
            );
        } catch (Exception ex) {
            return false;
        }
    }

    private JsonNode readTree(String value) {
        try {
            if (value == null || value.isBlank()) return objectMapper.createObjectNode();
            return objectMapper.readTree(value);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) return fallback;
        return value.asText();
    }

    private String nestedText(JsonNode parent, String objectField, String field) {
        JsonNode node = parent.path(objectField);
        if (node.isMissingNode() || node.isNull()) return "";
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    record PlazoInfo(RecepcionPlazoStatus status, String label, Integer diasRestantes, LocalDate limite) {
    }
}
