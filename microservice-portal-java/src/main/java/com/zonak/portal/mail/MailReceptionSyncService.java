package com.zonak.portal.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MailReceptionSyncService {
    private static final Logger log = LoggerFactory.getLogger(MailReceptionSyncService.class);
    private static final int MAX_BODIES_PER_SYNC = 80;
    private static final int MAX_MESSAGES_PER_FOLDER = 200;
    private static final int LOOKBACK_DAYS = 21;
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

    public SyncResult syncInbox(UUID companyId) {
        IncomingMailbox mailbox = resolveMailbox(companyId);
        Session session = Session.getInstance(mailbox.toSessionProperties());
        int imported = 0;
        int xmlFound = 0;
        int skipped = 0;
        int messages = 0;

        try (Store store = session.getStore(mailbox.protocol())) {
            store.connect(mailbox.host(), mailbox.port(), mailbox.username(), mailbox.password());
            List<Folder> folders = receptionFolders(store);
            if (folders.isEmpty()) {
                throw new IllegalStateException("No se encontró INBOX ni carpetas de recepción en el buzón.");
            }
            Date since = Date.from(Instant.now().minus(LOOKBACK_DAYS, ChronoUnit.DAYS));
            Set<String> seenMessageIds = new LinkedHashSet<>();
            int bodies = 0;
            for (Folder folder : folders) {
                if (bodies >= MAX_BODIES_PER_SYNC) {
                    break;
                }
                folder.open(Folder.READ_ONLY);
                try {
                    Message[] folderMessages = recentFolderMessages(folder, since);
                    if (folderMessages.length == 0) {
                        continue;
                    }
                    FetchProfile profile = new FetchProfile();
                    profile.add(FetchProfile.Item.ENVELOPE);
                    profile.add(FetchProfile.Item.CONTENT_INFO);
                    folder.fetch(folderMessages, profile);
                    for (int i = folderMessages.length - 1; i >= 0 && bodies < MAX_BODIES_PER_SYNC; i--) {
                        Message message = folderMessages[i];
                        messages++;
                        String messageId = messageIdOf(message);
                        if (StringUtils.hasText(messageId) && !seenMessageIds.add(messageId)) {
                            continue;
                        }
                        try {
                            if (!mightContainFiscalAttachment(message)) {
                                continue;
                            }
                            bodies++;
                            FiscalPackage pack = extractFiscalPackage(message);
                            if (pack.xmls.isEmpty()) {
                                log.info(
                                        "Correo fiscal sin XML/ZIP UBL en {}: asunto={}",
                                        folder.getFullName(),
                                        decodeSubject(message)
                                );
                            }
                            xmlFound += pack.xmls.size();
                            for (String xml : pack.xmls) {
                                if (insertReceivedInvoice(companyId, xml, "MAIL_INBOX", pack.pdf)) {
                                    imported++;
                                } else {
                                    skipped++;
                                }
                            }
                        } catch (Exception ex) {
                            log.warn("No se procesó un correo de recepción: {}", ex.getMessage());
                        }
                    }
                } finally {
                    if (folder.isOpen()) {
                        folder.close(false);
                    }
                }
            }
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw ex;
        } catch (AuthenticationFailedException ex) {
            log.warn("IMAP autenticación fallida ({}): {}", mailbox.sourceLabel(), ex.getMessage());
            throw new IllegalStateException(
                    "Gmail/IMAP rechazó las credenciales de "
                            + mailbox.username()
                            + ". En Configuración > Sociedades vuelva a guardar una contraseña de aplicación "
                            + "(16 caracteres). No use la clave normal de Gmail y deje IMAP activo.",
                    ex
            );
        } catch (Exception ex) {
            log.warn("Error sincronizando correo de recepción ({}): {}", mailbox.sourceLabel(), ex.getMessage());
            throw new IllegalStateException(
                    "No fue posible sincronizar el correo de recepción (" + mailbox.sourceLabel() + "): "
                            + readableMailError(ex),
                    ex
            );
        }

        return new SyncResult(messages, xmlFound, imported, skipped);
    }

    public String testIncomingConnection(UUID companyId) {
        IncomingMailbox mailbox = resolveMailbox(companyId);
        Session session = Session.getInstance(mailbox.toSessionProperties());
        try (Store store = session.getStore(mailbox.protocol())) {
            store.connect(mailbox.host(), mailbox.port(), mailbox.username(), mailbox.password());
            Folder inbox = store.getFolder("INBOX");
            if (inbox == null) {
                throw new IllegalStateException("No se encontró la carpeta INBOX en el buzón.");
            }
            inbox.open(Folder.READ_ONLY);
            try {
                int count = inbox.getMessageCount();
                return "Conexión IMAP correcta a " + mailbox.sourceLabel()
                        + " con el usuario " + mailbox.username()
                        + ". Mensajes en INBOX: " + count + ".";
            } finally {
                if (inbox.isOpen()) {
                    inbox.close(false);
                }
            }
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw ex;
        } catch (AuthenticationFailedException ex) {
            throw new IllegalStateException(
                    "Gmail/IMAP rechazó las credenciales de " + mailbox.username()
                            + ". Verifique la contraseña de aplicación e IMAP activo.",
                    ex
            );
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "No fue posible probar el correo de recepción (" + mailbox.sourceLabel() + "): "
                            + readableMailError(ex),
                    ex
            );
        }
    }

    private List<Folder> receptionFolders(Store store) throws Exception {
        List<Folder> selected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String name : List.of(
                "[Gmail]/Todos",
                "[Gmail]/All Mail",
                "[Google Mail]/Todos",
                "[Google Mail]/All Mail",
                "INBOX"
        )) {
            addFolderIfUsable(store.getFolder(name), selected, seen);
        }
        return selected;
    }

    private void addFolderIfUsable(Folder folder, List<Folder> selected, Set<String> seen) {
        try {
            if (folder == null || !folder.exists() || !StringUtils.hasText(folder.getFullName())) {
                return;
            }
            String key = folder.getFullName().toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                return;
            }
            if ((folder.getType() & Folder.HOLDS_MESSAGES) == 0) {
                return;
            }
            selected.add(folder);
        } catch (Exception ex) {
            log.debug("Carpeta IMAP ignorada: {}", ex.getMessage());
        }
    }

    private Message[] recentFolderMessages(Folder folder, Date since) throws Exception {
        Message[] searched = new Message[0];
        try {
            searched = folder.search(new ReceivedDateTerm(ComparisonTerm.GE, since));
        } catch (Exception ex) {
            log.info("IMAP no filtró por fecha en {}: {}", folder.getFullName(), ex.getMessage());
        }
        if (searched.length > 0) {
            return newest(searched, MAX_MESSAGES_PER_FOLDER);
        }
        int total = folder.getMessageCount();
        if (total <= 0) {
            return new Message[0];
        }
        int from = Math.max(1, total - MAX_MESSAGES_PER_FOLDER + 1);
        return folder.getMessages(from, total);
    }

    private Message[] newest(Message[] messages, int limit) {
        if (messages.length <= limit) {
            return messages;
        }
        Message[] sliced = new Message[limit];
        System.arraycopy(messages, messages.length - limit, sliced, 0, limit);
        return sliced;
    }

    private boolean mightContainFiscalAttachment(Message message) throws Exception {
        if (DianReceptionSpec.isReceptionMail(decodeSubject(message))) {
            return true;
        }
        return hasDianNamedAttachment(message);
    }

    private String decodeSubject(Message message) {
        try {
            String subject = message.getSubject();
            if (!StringUtils.hasText(subject)) {
                return "";
            }
            return MimeUtility.decodeText(subject).replace('\n', ' ').replace('\r', ' ').trim();
        } catch (Exception ex) {
            return "";
        }
    }

    private String messageIdOf(Message message) {
        try {
            String[] headers = message.getHeader("Message-ID");
            if (headers != null && headers.length > 0 && StringUtils.hasText(headers[0])) {
                return headers[0].trim();
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }

    private boolean hasDianNamedAttachment(Part part) throws Exception {
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                if (hasDianNamedAttachment(multipart.getBodyPart(i))) {
                    return true;
                }
            }
            return false;
        }
        if (part.isMimeType("message/rfc822")) {
            Object content = part.getContent();
            if (content instanceof Part nested) {
                return hasDianNamedAttachment(nested);
            }
        }
        return DianReceptionSpec.isDianPackageFile(partFileName(part));
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

    public int importXmlDocuments(UUID companyId, byte[] content, String fileName, String source) {
        if (companyId == null) {
            throw new IllegalArgumentException("La sociedad receptora es obligatoria.");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("El archivo XML/ZIP está vacío.");
        }
        FiscalPackage pack = extractFiscalPackage(content, fileName);
        if (pack.xmls.isEmpty()) {
            throw new IllegalArgumentException(
                    "No se encontró XML de factura (Invoice o AttachedDocument) en el archivo."
            );
        }
        int imported = 0;
        for (String xml : pack.xmls) {
            if (insertReceivedInvoice(companyId, xml, source, pack.pdf)) {
                imported++;
            }
        }
        if (imported == 0) {
            throw new IllegalStateException(
                    "Los XML ya estaban registrados o no se pudieron guardar para esta sociedad."
            );
        }
        return imported;
    }

    private boolean insertReceivedInvoice(UUID companyId, String xml, String source, byte[] pdf) {
        ParsedXml parsed = parseXml(xml);
        if (StringUtils.hasText(parsed.cufe()) && alreadyImported(companyId, parsed.cufe())) {
            if (pdf != null && pdf.length > 0) {
                attachPdfIfMissing(companyId, parsed.cufe(), pdf);
            }
            log.info("XML de recepción omitido: CUFE duplicado {}", parsed.cufe());
            return false;
        }
        long numero = nextReceivedNumber(companyId);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("role", "RECIBIDA");
        payload.put("source", source);
        payload.put("xml_base", xml);
        payload.put("invoice_number", parsed.invoiceNumber());
        payload.put("cufe", parsed.cufe());
        payload.put("fecha_emision", parsed.fechaEmision());
        payload.put("total", parsed.total());
        ObjectNode proveedor = payload.putObject("proveedor");
        proveedor.put("razon_social", parsed.proveedorNombre());
        proveedor.put("nit", parsed.proveedorNit());
        if (pdf != null && pdf.length > 4 && pdf[0] == 0x25 && pdf[1] == 0x50) {
            payload.put("pdf_base", Base64.getEncoder().encodeToString(pdf));
        }

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
                    "RCV",
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

    private boolean alreadyImported(UUID companyId, String cufe) {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                        SELECT EXISTS(
                            SELECT 1
                            FROM invoices
                            WHERE company_id = ?
                              AND uuid_cude = ?
                        )
                        """,
                Boolean.class,
                companyId,
                cufe
        );
        return Boolean.TRUE.equals(exists);
    }

    private void attachPdfIfMissing(UUID companyId, String cufe, byte[] pdf) {
        try {
            String encoded = Base64.getEncoder().encodeToString(pdf);
            jdbcTemplate.update(
                    """
                            UPDATE invoices
                            SET raw_dian_payload_jsonb = jsonb_set(
                                    COALESCE(raw_dian_payload_jsonb, '{}'::jsonb),
                                    '{pdf_base}',
                                    to_jsonb(?::text)
                                ),
                                updated_at = now()
                            WHERE company_id = ?
                              AND uuid_cude = ?
                              AND COALESCE(raw_dian_payload_jsonb->>'pdf_base', '') = ''
                            """,
                    encoded,
                    companyId,
                    cufe
            );
        } catch (Exception ex) {
            log.warn("No se adjuntó PDF de recepción al CUFE {}: {}", cufe, ex.getMessage());
        }
    }

    private String readableMailError(Exception ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        if (message.toLowerCase(Locale.ROOT).contains("authenticationfailed")
                || message.toLowerCase(Locale.ROOT).contains("invalid credentials")) {
            return "credenciales IMAP inválidas. Use una contraseña de aplicación de Gmail.";
        }
        return message;
    }

    private FiscalPackage extractFiscalPackage(byte[] content, String fileName) {
        FiscalPackage pack = new FiscalPackage();
        collectFromBytes(content, fileName == null ? "" : fileName.toLowerCase(Locale.ROOT), pack);
        return pack;
    }

    private FiscalPackage extractFiscalPackage(Message message) throws Exception {
        FiscalPackage pack = new FiscalPackage();
        collectXmlParts(message, pack);
        return pack;
    }

    private boolean looksLikeZip(byte[] content) {
        return content != null && content.length >= 4
                && content[0] == 0x50 && content[1] == 0x4B;
    }

    private void collectXmlFromZip(byte[] content, FiscalPackage pack) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] entryBytes = zip.readAllBytes();
                String entryName = DianReceptionSpec.baseName(entry.getName());
                String lower = entryName.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".jpg") || lower.endsWith(".png")) {
                    continue;
                }
                if (lower.endsWith(".pdf") || isPdf(entryBytes)) {
                    pack.addPdf(entryBytes);
                    continue;
                }
                if (lower.endsWith(".zip") || looksLikeZip(entryBytes)) {
                    collectXmlFromZip(entryBytes, pack);
                } else {
                    String xml = decodeXml(entryBytes);
                    if (DianReceptionSpec.isReceivableUbl(xml)
                            || DianReceptionSpec.isDianXmlName(entryName) && looksLikeXml(xml)) {
                        pack.xmls.add(xml);
                    }
                }
                zip.closeEntry();
            }
        } catch (Exception ex) {
            log.warn("No se pudo leer ZIP de recepción: {}", ex.getMessage());
        }
    }

    private void collectXmlParts(Part part, FiscalPackage pack) throws Exception {
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                collectXmlParts(multipart.getBodyPart(i), pack);
            }
            return;
        }
        if (part.isMimeType("message/rfc822")) {
            Object nested = part.getContent();
            if (nested instanceof Part nestedPart) {
                collectXmlParts(nestedPart, pack);
            }
            return;
        }

        String fileName = partFileName(part);
        String contentType = part.getContentType() == null ? "" : part.getContentType().toLowerCase(Locale.ROOT);
        if (contentType.contains("text/html")
                || contentType.contains("text/calendar")
                || contentType.startsWith("image/")) {
            return;
        }

        boolean looksZip = contentType.contains("zip")
                || fileName.endsWith(".zip")
                || DianReceptionSpec.isDianPackageFile(fileName);
        boolean looksXml = contentType.contains("xml")
                || fileName.endsWith(".xml")
                || DianReceptionSpec.isDianXmlName(fileName);
        boolean looksPdf = contentType.contains("application/pdf") || fileName.endsWith(".pdf");
        boolean binaryAttachment = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())
                || contentType.contains("octet-stream")
                || contentType.contains("application/");

        if (!looksXml && !looksZip && !looksPdf && part.isMimeType("text/plain") && !binaryAttachment) {
            Object content = part.getContent();
            if (content instanceof String text && DianReceptionSpec.isReceivableUbl(text)) {
                pack.xmls.add(text);
            }
            return;
        }

        if (!looksXml && !looksZip && !looksPdf && !binaryAttachment) {
            return;
        }

        int declaredSize = part.getSize();
        if (declaredSize > DianReceptionSpec.MAX_MAIL_ZIP_BYTES) {
            log.info("Adjunto omitido por peso > 2 MB (anexo 9.1): {}", fileName);
            return;
        }

        try (InputStream inputStream = part.getInputStream()) {
            byte[] bytes = inputStream.readAllBytes();
            if (bytes.length > DianReceptionSpec.MAX_MAIL_ZIP_BYTES) {
                log.info("Adjunto omitido por peso > 2 MB (anexo 9.1): {}", fileName);
                return;
            }
            collectFromBytes(bytes, fileName, pack);
        }
    }

    private String partFileName(Part part) throws Exception {
        String name = decodeFileName(part.getFileName());
        if (StringUtils.hasText(name)) {
            return name;
        }
        String[] types = part.getHeader("Content-Type");
        if (types != null && types.length > 0 && types[0] != null) {
            Matcher matcher = Pattern.compile("name\\s*=\\s*\"?([^\\\";]+)\"?", Pattern.CASE_INSENSITIVE)
                    .matcher(types[0]);
            if (matcher.find()) {
                return decodeFileName(matcher.group(1));
            }
        }
        return "";
    }

    private void collectFromBytes(byte[] bytes, String fileName, FiscalPackage pack) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        String name = DianReceptionSpec.baseName(fileName).toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf") || isPdf(bytes)) {
            pack.addPdf(bytes);
            return;
        }
        if (name.endsWith(".zip") || DianReceptionSpec.isDianPackageFile(name) || looksLikeZip(bytes)) {
            collectXmlFromZip(bytes, pack);
            return;
        }
        String xml = decodeXml(bytes);
        if (DianReceptionSpec.isReceivableUbl(xml)) {
            pack.xmls.add(xml);
        }
    }

    private boolean isPdf(byte[] bytes) {
        return bytes != null && bytes.length > 4
                && bytes[0] == 0x25 && bytes[1] == 0x50 && bytes[2] == 0x44 && bytes[3] == 0x46;
    }

    private String decodeFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "";
        }
        try {
            return MimeUtility.decodeText(fileName).trim().toLowerCase(Locale.ROOT);
        } catch (Exception ex) {
            return fileName.trim().toLowerCase(Locale.ROOT);
        }
    }

    private String decodeXml(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private boolean looksLikeXml(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        return DianReceptionSpec.isReceivableUbl(value);
    }

    private ParsedXml parseXml(String xml) {
        String invoiceXml = extractEmbeddedInvoice(xml);
        String source = StringUtils.hasText(invoiceXml) ? invoiceXml : xml;
        String id = firstInvoiceId(source);
        String prefijo = "FV";
        if (StringUtils.hasText(id)) {
            Matcher matcher = Pattern.compile("^([A-Za-z]+)(\\d+)$").matcher(id.trim());
            if (matcher.matches()) {
                prefijo = matcher.group(1);
            }
        }

        String nombre = firstNonBlank(
                firstTag(source, "RegistrationName"),
                firstTag(source, "Name"),
                "Proveedor"
        );
        String nit = firstNonBlank(firstTag(source, "CompanyID"), "—");
        String cufe = firstNonBlank(
                taggedUuid(source, "CUFE-SHA384"),
                taggedUuid(xml, "CUFE-SHA384"),
                firstTag(source, "UUID")
        );
        String fechaEmision = firstNonBlank(firstTag(source, "IssueDate"));
        String total = firstNonBlank(firstTag(source, "PayableAmount"), firstTag(source, "TaxInclusiveAmount"), "0");
        String invoiceNumber = StringUtils.hasText(id) ? id.trim() : prefijo;
        return new ParsedXml(prefijo, invoiceNumber, nombre, nit, cufe, fechaEmision, total);
    }

    private String extractEmbeddedInvoice(String xml) {
        Matcher cdata = Pattern.compile("<!\\[CDATA\\[(.*?)]]>", Pattern.DOTALL).matcher(xml);
        while (cdata.find()) {
            String inner = unescapeXml(cdata.group(1).trim());
            if (isEmbeddedFiscalDocument(inner)) {
                return inner;
            }
        }
        Matcher description = Pattern.compile(
                "<(?:[A-Za-z0-9]+:)?Description\\b[^>]*>(.*?)</(?:[A-Za-z0-9]+:)?Description>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        ).matcher(xml);
        while (description.find()) {
            String inner = unescapeXml(description.group(1).trim());
            if (isEmbeddedFiscalDocument(inner)) {
                return inner;
            }
        }
        return "";
    }

    private boolean isEmbeddedFiscalDocument(String inner) {
        return DianReceptionSpec.isReceivableUbl(inner)
                && (inner.contains("Invoice") || inner.contains("CreditNote") || inner.contains("DebitNote"));
    }

    private String unescapeXml(String value) {
        return value.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    private String firstInvoiceId(String xml) {
        Matcher matcher = Pattern.compile(
                "<(?:[A-Za-z0-9]+:)?ID\\b[^>]*>([A-Za-z]{1,12}\\d{3,})</(?:[A-Za-z0-9]+:)?ID>",
                Pattern.CASE_INSENSITIVE
        ).matcher(xml);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return firstTag(xml, "ID");
    }

    private String taggedUuid(String xml, String schemeName) {
        Matcher matcher = Pattern.compile(
                "<(?:[A-Za-z0-9]+:)?UUID\\b[^>]*schemeName=\"" + Pattern.quote(schemeName) + "\"[^>]*>([^<]+)</(?:[A-Za-z0-9]+:)?UUID>",
                Pattern.CASE_INSENSITIVE
        ).matcher(xml);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
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
            String invoiceNumber,
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

    private static final class FiscalPackage {
        private final List<String> xmls = new ArrayList<>();
        private byte[] pdf;

        private void addPdf(byte[] bytes) {
            if (bytes == null || bytes.length < 5) {
                return;
            }
            if (pdf == null || bytes.length > pdf.length) {
                pdf = bytes;
            }
        }
    }

    public record SyncResult(int messages, int xmlFound, int imported, int skipped) {
        public String summary() {
            return "Sincronización completada. Correos revisados: " + messages
                    + " (All Mail/INBOX, filtro 9.1 y ZIP/XML DIAN). "
                    + "XML encontrados: " + xmlFound
                    + ". Documentos importados: " + imported
                    + ". Omitidos: " + skipped + ".";
        }
    }
}
