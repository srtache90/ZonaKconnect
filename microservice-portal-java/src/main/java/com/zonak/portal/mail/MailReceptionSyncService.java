package com.zonak.portal.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MailReceptionSyncService {
    private static final Logger log = LoggerFactory.getLogger(MailReceptionSyncService.class);
    private final MailAppProperties mailAppProperties;
    private final SociedadMailAccountRepository sociedadMailAccountRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MailReceptionSyncService(
            MailAppProperties mailAppProperties,
            SociedadMailAccountRepository sociedadMailAccountRepository,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.mailAppProperties = mailAppProperties;
        this.sociedadMailAccountRepository = sociedadMailAccountRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public int syncInbox(UUID companyId) {
        IncomingMailbox mailbox = resolveMailbox(companyId);
        Session session = Session.getInstance(mailbox.toSessionProperties());
        int imported = 0;

        try (Store store = session.getStore(mailbox.protocol())) {
            store.connect(mailbox.host(), mailbox.port(), mailbox.username(), mailbox.password());

            Folder inbox = store.getFolder("INBOX");
            if (inbox == null) {
                throw new IllegalStateException("No se encontró la carpeta INBOX en el buzón de recepción.");
            }
            inbox.open(Folder.READ_ONLY);
            try {
                Message[] messages = inbox.getMessages();
                for (Message message : messages) {
                    List<String> xmlAttachments = extractXmlParts(message);
                    for (String xml : xmlAttachments) {
                        if (insertReceivedInvoice(companyId, xml)) {
                            imported++;
                        }
                    }
                }
            } finally {
                if (inbox.isOpen()) {
                    inbox.close(false);
                }
            }
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Error sincronizando correo de recepción ({}): {}", mailbox.sourceLabel(), ex.getMessage());
            throw new IllegalStateException(
                    "No fue posible sincronizar el correo de recepción (" + mailbox.sourceLabel() + "): "
                            + ex.getMessage(),
                    ex
            );
        }

        return imported;
    }

    private IncomingMailbox resolveMailbox(UUID companyId) {
        if (companyId == null) {
            throw new IllegalArgumentException("La sociedad receptora es obligatoria.");
        }

        Optional<SociedadMailAccount> account = sociedadMailAccountRepository.findBySociedadId(companyId);
        if (account.isPresent() && account.get().hasIncomingMail()) {
            return IncomingMailbox.fromSociedad(account.get());
        }

        String missing = account.map(SociedadMailAccount::describeMissingIncoming)
                .orElse("la sociedad no existe");
        boolean attemptedSociedadConfig = account.isPresent() && account.get().hasAnyIncomingAttempt();

        if (!attemptedSociedadConfig
                && mailAppProperties.isReceptionEnabled()
                && StringUtils.hasText(mailAppProperties.getReceptionHost())) {
            log.info("Sociedad {} sin IMAP; se usa buzón global de desarrollo.", companyId);
            return IncomingMailbox.fromGlobal(mailAppProperties);
        }

        throw new IllegalStateException(
                "El correo entrante de la sociedad no está configurado. "
                        + "En Configuración > Sociedades debe guardar host IMAP, puerto, usuario y contraseña. "
                        + "Detalle: " + missing + "."
        );
    }

    private boolean insertReceivedInvoice(UUID companyId, String xml) {
        ParsedXml parsed = parseXml(xml);
        long numero = nextReceivedNumber(companyId);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("role", "RECIBIDA");
        payload.put("source", "MAIL_INBOX");
        payload.put("xml_base", xml);
        payload.put("cufe", parsed.cufe());
        payload.put("fecha_emision", parsed.fechaEmision());
        payload.put("total", parsed.total());
        ObjectNode proveedor = payload.putObject("proveedor");
        proveedor.put("razon_social", parsed.proveedorNombre());
        proveedor.put("nit", parsed.proveedorNit());

        try {
            ObjectNode totals = objectMapper.createObjectNode();
            totals.put("total", parsed.totalAmount());
            String json = objectMapper.writeValueAsString(payload);
            int rows = jdbcTemplate.update(
                    """
                            INSERT INTO invoices (
                                company_id,
                                emission_point_id,
                                prefijo,
                                numero,
                                uuid_cude,
                                estado_dian,
                                document_kind,
                                totals_jsonb,
                                raw_dian_payload_jsonb
                            ) VALUES (?, NULL, ?, ?, NULLIF(?, ''), 'PENDIENTE', 'INVOICE', ?::jsonb, ?::jsonb)
                            """,
                    companyId,
                    parsed.prefijo(),
                    numero,
                    parsed.cufe(),
                    objectMapper.writeValueAsString(totals),
                    json
            );
            return rows > 0;
        } catch (Exception ex) {
            log.warn("No se importó XML de recepción: {}", ex.getMessage());
            return false;
        }
    }

    private long nextReceivedNumber(UUID companyId) {
        Long next = jdbcTemplate.queryForObject(
                """
                        SELECT COALESCE(MAX(numero), 0) + 1
                        FROM invoices
                        WHERE company_id = ? AND emission_point_id IS NULL
                        """,
                Long.class,
                companyId
        );
        if (next == null || next <= 0) {
            return System.currentTimeMillis() % 1_000_000_000L;
        }
        return next;
    }

    private List<String> extractXmlParts(Message message) throws Exception {
        List<String> xmlParts = new ArrayList<>();
        collectXmlParts(message, xmlParts);
        return xmlParts;
    }

    private void collectXmlParts(Part part, List<String> xmlParts) throws Exception {
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                collectXmlParts(multipart.getBodyPart(i), xmlParts);
            }
            return;
        }

        String fileName = part.getFileName();
        String contentType = part.getContentType() == null ? "" : part.getContentType().toLowerCase(Locale.ROOT);
        String disposition = part.getDisposition();
        boolean looksXml = contentType.contains("xml")
                || (fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".xml"))
                || (Part.ATTACHMENT.equalsIgnoreCase(disposition)
                && fileName != null
                && fileName.toLowerCase(Locale.ROOT).contains("xml"));

        if (!looksXml && part.isMimeType("text/plain")) {
            Object content = part.getContent();
            if (content instanceof String text && looksLikeXml(text)) {
                xmlParts.add(text);
            }
            return;
        }

        if (!looksXml) {
            return;
        }

        try (InputStream inputStream = part.getInputStream()) {
            String xml = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            if (looksLikeXml(xml)) {
                xmlParts.add(xml);
            }
        }
    }

    private boolean looksLikeXml(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("<") && (trimmed.contains("Invoice") || trimmed.contains("AttachedDocument")
                || trimmed.contains("cbc:") || trimmed.toLowerCase(Locale.ROOT).contains("factura"));
    }

    private ParsedXml parseXml(String xml) {
        String id = firstTag(xml, "ID");
        String prefijo = "FV";
        if (StringUtils.hasText(id)) {
            Matcher matcher = Pattern.compile("^([A-Za-z]+)(\\d+)$").matcher(id.trim());
            if (matcher.matches()) {
                prefijo = matcher.group(1);
            } else if (id.length() <= 12 && id.chars().allMatch(Character::isLetter)) {
                prefijo = id;
            }
        }

        String nombre = firstNonBlank(
                firstTag(xml, "RegistrationName"),
                firstTag(xml, "Name"),
                "Proveedor"
        );
        String nit = firstNonBlank(firstTag(xml, "CompanyID"), "—");
        String cufe = firstNonBlank(firstTag(xml, "UUID"));
        String fechaEmision = firstNonBlank(firstTag(xml, "IssueDate"));
        String total = firstNonBlank(firstTag(xml, "PayableAmount"), firstTag(xml, "TaxInclusiveAmount"), "0");
        return new ParsedXml(prefijo, nombre, nit, cufe, fechaEmision, total);
    }

    private String firstTag(String xml, String tagName) {
        Pattern pattern = Pattern.compile(
                "<(?:[A-Za-z0-9]+:)?" + Pattern.quote(tagName) + "\\b[^>]*>([^<]*)</(?:[A-Za-z0-9]+:)?" + Pattern.quote(tagName) + ">",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(xml);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private record ParsedXml(
            String prefijo,
            String proveedorNombre,
            String proveedorNit,
            String cufe,
            String fechaEmision,
            String total
    ) {
        private java.math.BigDecimal totalAmount() {
            try {
                return new java.math.BigDecimal(total.replace(",", ".").trim());
            } catch (Exception ex) {
                return java.math.BigDecimal.ZERO;
            }
        }
    }
}
