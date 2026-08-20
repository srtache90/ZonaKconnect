package com.zonak.portal.recepcion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zonak.portal.security.SensitiveDataCryptoService;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RecepcionEventService {
    private static final Logger log = LoggerFactory.getLogger(RecepcionEventService.class);
    private static final Set<String> PENDING_STATES = Set.of("PENDIENTE", "RECIBIDO_PND");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RadianDianClient radianDianClient;
    private final SensitiveDataCryptoService cryptoService;
    private final ReceivedInvoiceRepository receivedInvoiceRepository;
    private final Boolean auditTableExists;

    public RecepcionEventService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RadianDianClient radianDianClient,
            SensitiveDataCryptoService cryptoService,
            ReceivedInvoiceRepository receivedInvoiceRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.radianDianClient = radianDianClient;
        this.cryptoService = cryptoService;
        this.receivedInvoiceRepository = receivedInvoiceRepository;
        this.auditTableExists = detectAuditTable();
    }

    public String registrarAcuse085(UUID companyId, UUID invoiceId) {
        return transition(
                companyId,
                invoiceId,
                "ACUSADA_085",
                "085",
                PENDING_STATES,
                "ACUSE_085",
                Map.of(),
                "Acuse de recibo 085 (DIAN 030)"
        );
    }

    public String registrarReciboBienes086(UUID companyId, UUID invoiceId, String recibidoPor, String documentoRecibidor) {
        ObjectNode payload = objectMapper.createObjectNode();
        if (StringUtils.hasText(recibidoPor)) {
            payload.put("recibidoPor", recibidoPor.trim());
        }
        if (StringUtils.hasText(documentoRecibidor)) {
            payload.put("documentoRecibidor", documentoRecibidor.trim());
        }
        return transition(
                companyId,
                invoiceId,
                "RECIBIDA_086",
                "086",
                Set.of("ACUSADA_085"),
                "RECIBO_BIENES_086",
                payload,
                "Recibo de bienes/servicios 086 (DIAN 032)"
        );
    }

    public String registrarAceptacion087(UUID companyId, UUID invoiceId) {
        return transition(
                companyId,
                invoiceId,
                "ACEPTADA_087",
                "087",
                Set.of("RECIBIDA_086"),
                "ACEPTACION_087",
                Map.of(),
                "Aceptación expresa 087 (DIAN 033)"
        );
    }

    public String registrarRechazo088(UUID companyId, UUID invoiceId, String motivoRechazo) {
        if (!StringUtils.hasText(motivoRechazo)) {
            throw new IllegalArgumentException("Debe indicar el motivo de rechazo (motivo_rechazo).");
        }
        return transition(
                companyId,
                invoiceId,
                "RECHAZADA_088",
                "088",
                Set.of("RECIBIDA_086"),
                "RECHAZO_088",
                Map.of("motivo_rechazo", motivoRechazo.trim()),
                "Reclamo/rechazo 088 (DIAN 031)"
        );
    }

    private String transition(
            UUID companyId,
            UUID invoiceId,
            String targetState,
            String eventCode,
            Set<String> allowedFrom,
            String action,
            Object payload,
            String successLabel
    ) {
        ReceivedInvoiceContext current = loadInvoice(companyId, invoiceId);
        String normalized = normalize(current.estadoDian());
        if (!allowedFrom.contains(normalized)) {
            throw new IllegalStateException(invalidTransitionMessage(normalized, targetState));
        }
        if (!StringUtils.hasText(current.invoiceNumber())) {
            throw new IllegalStateException(
                    "La factura recibida no tiene número de documento; no se puede enviar el evento a la DIAN."
            );
        }
        RecepcionCufeValidator.requireValidCufeOrThrow(current.cufe());
        if (StringUtils.hasText(current.sociedadNit()) && StringUtils.hasText(current.receptorNit())
                && !RecepcionCufeValidator.sameNit(current.sociedadNit(), current.receptorNit())) {
            throw new IllegalStateException(
                    "NIT receptor del XML (" + current.receptorNit() + ") no coincide con la sociedad ("
                            + current.sociedadNit() + "). Corrija el documento antes de transmitir RADIAN."
            );
        }
        receivedInvoiceRepository.findDuplicateByCufe(companyId, current.cufe(), invoiceId).ifPresent(dup -> {
            throw new IllegalStateException(
                    "CUFE duplicado en otra factura recibida (" + dup + "). No se transmite el evento."
            );
        });

        Map<String, Object> request = new HashMap<>();
        request.put("ambiente", current.ambiente());
        request.put("eventCode", eventCode);
        request.put("cufe", current.cufe());
        request.put("invoiceNumber", current.invoiceNumber());
        request.put("documentTypeCode", "01");
        request.put("senderNit", current.senderNit());
        request.put("senderName", current.senderName());
        request.put("receiverNit", current.receiverNit());
        request.put("receiverName", current.receiverName());
        if (payload instanceof Map<?, ?> map) {
            Object motivo = map.get("motivo_rechazo");
            if (motivo != null) {
                request.put("motivo", motivo.toString());
            }
            Object recibidoPor = map.get("recibidoPor");
            if (recibidoPor != null) {
                request.put("recibidoPor", recibidoPor.toString());
            }
            Object documento = map.get("documentoRecibidor");
            if (documento != null) {
                request.put("documentoRecibidor", documento.toString());
            }
        } else if (payload instanceof ObjectNode node) {
            if (node.hasNonNull("motivo_rechazo")) {
                request.put("motivo", node.get("motivo_rechazo").asText());
            }
            if (node.hasNonNull("recibidoPor")) {
                request.put("recibidoPor", node.get("recibidoPor").asText());
            }
            if (node.hasNonNull("documentoRecibidor")) {
                request.put("documentoRecibidor", node.get("documentoRecibidor").asText());
            }
        }
        if (StringUtils.hasText(current.softwareId())) {
            request.put("softwareId", current.softwareId());
        }
        if (StringUtils.hasText(current.softwarePin())) {
            request.put("softwarePin", current.softwarePin());
        }
        if (StringUtils.hasText(current.invoiceIssueDate())) {
            request.put("invoiceIssueDate", current.invoiceIssueDate());
        }

        boolean mock = "Mock".equalsIgnoreCase(current.ambiente());
        if (!mock) {
            SociedadCertificate cert = loadActiveCertificate(companyId);
            if (cert == null) {
                throw new IllegalStateException(
                        "La sociedad no tiene certificado digital activo. Cárguelo en Configuraciones → Certificados "
                                + "antes de enviar eventos RADIAN a " + current.ambiente() + "."
                );
            }
            request.put("certificatePfxBase64", cert.pfxBase64());
            request.put("certificatePassword", cert.password());
        }

        JsonNode dianResponse = radianDianClient.sendEvent(request);
        boolean exitoso = dianResponse != null && dianResponse.path("exitoso").asBoolean(false);
        if (!exitoso) {
            String errores = dianResponse == null ? "sin respuesta" : dianResponse.path("errores").toString();
            String status = dianResponse == null ? "" : dianResponse.path("status").asText("");
            throw new IllegalStateException(
                    "La DIAN no aceptó el evento " + eventCode + ". " + status + " " + errores
            );
        }

        String eventJson = buildTimelineEventJson(eventCode, action, successLabel, dianResponse, payload);
        String mergePatch = buildDianPayload(eventCode, dianResponse, payload);
        int updated = jdbcTemplate.update(
                """
                        UPDATE invoices
                        SET estado_dian = ?,
                            dian_response_jsonb = (
                                COALESCE(dian_response_jsonb, '{}'::jsonb) || ?::jsonb
                            ) || jsonb_build_object(
                                'radian_events',
                                COALESCE(dian_response_jsonb->'radian_events', '[]'::jsonb) || ?::jsonb
                            ),
                            updated_at = now()
                        WHERE id = ? AND company_id = ? AND emission_point_id IS NULL
                        """,
                targetState,
                mergePatch,
                eventJson,
                invoiceId,
                companyId
        );
        if (updated == 0) {
            throw new IllegalStateException("No fue posible actualizar el estado de la factura recibida.");
        }

        insertAudit(companyId, invoiceId, action, dianResponse);
        String track = text(dianResponse, "trackID", text(dianResponse, "trackId", ""));
        String cude = text(dianResponse, "cune", text(dianResponse, "cufeCune", ""));
        return successLabel + " enviado a DIAN (" + current.ambiente() + "). TrackID="
                + (StringUtils.hasText(track) ? track : "n/d")
                + (StringUtils.hasText(cude) ? (" CUDE=" + cude.substring(0, Math.min(16, cude.length())) + "…") : "")
                + ".";
    }

    private String buildDianPayload(String eventCode, JsonNode dianResponse, Object localPayload) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("last_radian_event", eventCode);
            root.set("radian_response", dianResponse == null ? objectMapper.createObjectNode() : dianResponse);
            if (localPayload != null) {
                root.set("radian_request", objectMapper.valueToTree(localPayload));
            }
            if ("086".equals(eventCode)) {
                root.put(
                        "recibo_086_at",
                        java.time.LocalDate.now(RecepcionBusinessDays.BOGOTA).toString()
                );
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String buildTimelineEventJson(
            String eventCode,
            String action,
            String label,
            JsonNode dianResponse,
            Object localPayload
    ) {
        try {
            ObjectNode event = objectMapper.createObjectNode();
            event.put("code", eventCode);
            event.put("action", action);
            event.put("label", label);
            event.put("at", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString());
            event.put("trackId", text(dianResponse, "trackID", text(dianResponse, "trackId", "")));
            event.put("cude", text(dianResponse, "cune", text(dianResponse, "cufeCune", "")));
            if (localPayload != null) {
                event.set("request", objectMapper.valueToTree(localPayload));
            }
            return "[" + objectMapper.writeValueAsString(event) + "]";
        } catch (Exception ex) {
            return "[]";
        }
    }

    private ReceivedInvoiceContext loadInvoice(UUID companyId, UUID invoiceId) {
        try {
            return jdbcTemplate.queryForObject(
                    """
                            SELECT i.estado_dian,
                                   COALESCE(i.uuid_cude, i.raw_dian_payload_jsonb->>'cufe', '') AS cufe,
                                   COALESCE(
                                       NULLIF(i.raw_dian_payload_jsonb->>'invoice_number', ''),
                                       NULLIF(i.prefijo, '') || i.numero::text,
                                       ''
                                   ) AS invoice_number,
                                   COALESCE(c.razon_social, s.razon_social, '') AS sender_name,
                                   COALESCE(c.nit, s.nit, '') AS sender_nit,
                                   COALESCE(i.raw_dian_payload_jsonb->'proveedor'->>'razon_social', '') AS receiver_name,
                                   COALESCE(i.raw_dian_payload_jsonb->'proveedor'->>'nit', '') AS receiver_nit,
                                   COALESCE(
                                       NULLIF(i.raw_dian_payload_jsonb->>'receptor_nit', ''),
                                       NULLIF(i.raw_dian_payload_jsonb->'receptor'->>'nit', ''),
                                       ''
                                   ) AS receptor_nit,
                                   COALESCE(NULLIF(s.nit, ''), NULLIF(c.nit, ''), '') AS sociedad_nit,
                                   COALESCE(
                                       NULLIF(c.dian_config->>'ambiente', ''),
                                       NULLIF(s.dian_ambiente, ''),
                                       'Habilitacion'
                                   ) AS ambiente,
                                   COALESCE(c.dian_config->>'software_id', '') AS software_id,
                                   COALESCE(c.dian_config->>'pin', '') AS software_pin,
                                   COALESCE(
                                       NULLIF(i.raw_dian_payload_jsonb->>'fecha_emision', ''),
                                       to_char((i.created_at AT TIME ZONE 'America/Bogota')::date, 'YYYY-MM-DD'),
                                       ''
                                   ) AS invoice_issue_date
                            FROM invoices i
                            LEFT JOIN companies c ON c.id = i.company_id
                            LEFT JOIN sociedades s ON s.id = i.company_id
                            WHERE i.id = ? AND i.company_id = ? AND i.emission_point_id IS NULL
                            """,
                    (rs, rowNum) -> new ReceivedInvoiceContext(
                            rs.getString("estado_dian"),
                            rs.getString("cufe"),
                            rs.getString("invoice_number"),
                            rs.getString("sender_name"),
                            rs.getString("sender_nit"),
                            rs.getString("receiver_name"),
                            rs.getString("receiver_nit"),
                            rs.getString("receptor_nit"),
                            rs.getString("sociedad_nit"),
                            rs.getString("ambiente"),
                            rs.getString("software_id"),
                            rs.getString("software_pin"),
                            rs.getString("invoice_issue_date")
                    ),
                    invoiceId,
                    companyId
            );
        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalArgumentException("Factura recibida no encontrada para la sociedad actual.");
        }
    }

    private void insertAudit(UUID companyId, UUID invoiceId, String action, Object payload) {
        if (!Boolean.TRUE.equals(auditTableExists)) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
            jdbcTemplate.update(
                    """
                            INSERT INTO audit_events (company_id, entity_type, entity_id, action, payload)
                            VALUES (?, 'invoice', ?, ?, ?::jsonb)
                            """,
                    companyId,
                    invoiceId,
                    action,
                    json
            );
        } catch (Exception ex) {
            log.warn("No fue posible registrar audit_events action={} invoiceId={}: {}", action, invoiceId, ex.getMessage());
        }
    }

    private SociedadCertificate loadActiveCertificate(UUID companyId) {
        try {
            return jdbcTemplate.query(
                    """
                            SELECT contenido_base64_enc, password_enc
                            FROM certificados_digitales
                            WHERE sociedad_id = ? AND activo = true AND valido_hasta >= CURRENT_DATE
                            ORDER BY valido_hasta DESC
                            LIMIT 1
                            """,
                    rs -> {
                        if (!rs.next()) {
                            return null;
                        }
                        String pfxBase64 = cryptoService.decryptToString(rs.getString("contenido_base64_enc"));
                        String password = cryptoService.decryptToString(rs.getString("password_enc"));
                        if (!StringUtils.hasText(pfxBase64) || password == null) {
                            return null;
                        }
                        return new SociedadCertificate(pfxBase64.trim(), password);
                    },
                    companyId
            );
        } catch (Exception ex) {
            log.error("No fue posible cargar certificado digital sociedad={}: {}", companyId, ex.getMessage());
            throw new IllegalStateException(
                    "No fue posible descifrar el certificado digital de la sociedad. Verifique JWT_SECRET y el certificado cargado.",
                    ex
            );
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
            log.warn("No fue posible verificar tabla audit_events: {}", ex.getMessage());
            return false;
        }
    }

    private String normalize(String estado) {
        if (estado == null || estado.isBlank()) {
            return "PENDIENTE";
        }
        String value = estado.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "085" -> "ACUSADA_085";
            case "086" -> "RECIBIDA_086";
            case "087" -> "ACEPTADA_087";
            case "088", "RECHAZADO" -> "RECHAZADA_088";
            case "TACITA", "ACEPTACION_TACITA" -> "ACEPTADA_TACITA";
            default -> value;
        };
    }

    private String invalidTransitionMessage(String current, String target) {
        return "Transición inválida: el estado actual es " + current + " y no permite pasar a " + target + ".";
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return fallback;
        }
        String value = node.get(field).asText("");
        return StringUtils.hasText(value) ? value : fallback;
    }

    private record ReceivedInvoiceContext(
            String estadoDian,
            String cufe,
            String invoiceNumber,
            String senderName,
            String senderNit,
            String receiverName,
            String receiverNit,
            String receptorNit,
            String sociedadNit,
            String ambiente,
            String softwareId,
            String softwarePin,
            String invoiceIssueDate
    ) {
    }

    private record SociedadCertificate(String pfxBase64, String password) {
    }
}
