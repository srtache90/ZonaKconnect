package com.zonak.portal.mail;

import com.zonak.portal.dto.InvoicePdfData;
import com.zonak.portal.exception.InvoiceStorageException;
import com.zonak.portal.service.InvoiceClientService;
import com.zonak.portal.service.InvoiceOrchestratorService;
import com.zonak.portal.service.InvoiceReportRepository;
import jakarta.mail.internet.MimeMessage;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class InvoiceMailDispatchService {
    private static final Logger log = LoggerFactory.getLogger(InvoiceMailDispatchService.class);

    private final MailAppProperties mailProperties;
    private final JavaMailSender fallbackMailSender;
    private final SociedadMailAccountRepository sociedadMailAccountRepository;
    private final InvoiceOrchestratorService invoiceOrchestratorService;
    private final InvoiceClientService invoiceClientService;
    private final InvoiceReportRepository invoiceReportRepository;

    public InvoiceMailDispatchService(
            MailAppProperties mailProperties,
            JavaMailSender mailSender,
            SociedadMailAccountRepository sociedadMailAccountRepository,
            @Lazy InvoiceOrchestratorService invoiceOrchestratorService,
            InvoiceClientService invoiceClientService,
            InvoiceReportRepository invoiceReportRepository
    ) {
        this.mailProperties = mailProperties;
        this.fallbackMailSender = mailSender;
        this.sociedadMailAccountRepository = sociedadMailAccountRepository;
        this.invoiceOrchestratorService = invoiceOrchestratorService;
        this.invoiceClientService = invoiceClientService;
        this.invoiceReportRepository = invoiceReportRepository;
    }

    public Mono<String> dispatchToAcquirerIfConfigured(
            String tenantId,
            UUID invoiceId,
            String emissionPointId,
            String customerEmail
    ) {
        if (!StringUtils.hasText(customerEmail)) {
            log.info("mail.dispatch skipped invoice_id={}: adquirente sin correo", invoiceId);
            return Mono.empty();
        }
        return Mono.fromCallable(() -> sendInvoiceDocuments(tenantId, invoiceId, customerEmail.trim(), emissionPointId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public String sendInvoiceDocuments(String tenantId, UUID invoiceId, String toEmail) {
        return sendInvoiceDocuments(tenantId, invoiceId, toEmail, null);
    }

    public String sendInvoiceDocuments(String tenantId, UUID invoiceId, String toEmail, String emissionPointId) {
        if (!StringUtils.hasText(toEmail)) {
            throw new IllegalArgumentException("El correo destinatario es obligatorio");
        }

        UUID tenantUuid = UUID.fromString(tenantId);
        InvoicePdfData invoice = invoiceReportRepository.findApprovedInvoice(tenantUuid, invoiceId)
                .or(() -> invoiceReportRepository.findInvoice(tenantUuid, invoiceId))
                .orElseThrow(() -> new InvoiceStorageException("Documento no disponible para envío por correo"));

        SociedadMailAccount account = sociedadMailAccountRepository.findBySociedadId(tenantUuid).orElse(null);
        JavaMailSender sender;
        String from;
        if (account != null && account.hasOutgoingMail()) {
            sender = createSender(account);
            from = firstNonBlank(account.correoEmision(), account.usuarioSmtp(), mailProperties.getFrom());
        } else if (mailProperties.isDispatchEnabled()) {
            sender = fallbackMailSender;
            from = mailProperties.getFrom();
        } else {
            throw new IllegalStateException(
                    "El correo saliente de la sociedad no está configurado. "
                            + "En Configuración > Sociedades debe guardar host SMTP, puerto, usuario y contraseña."
            );
        }

        try {
            byte[] pdfBytes = invoiceOrchestratorService
                    .downloadOrGeneratePdf(tenantUuid, invoiceId)
                    .block();

            AttachmentDownload attachmentDownload = downloadAttachmentQuietly(invoiceId, tenantId, emissionPointId);
            if (attachmentDownload.bytes() == null || attachmentDownload.bytes().length == 0) {
                throw new IllegalStateException(
                        "Contenedor electrónico (AttachedDocument) no disponible. Verifique validación DIAN."
                );
            }
            String subject = DianAcquirerMailComposer.buildSubject(invoice, null);
            String body = DianAcquirerMailComposer.buildBody(
                    invoice,
                    account != null ? account.correoRecepcion() : null
            );
            String pdfName = DianAcquirerMailComposer.pdfAttachmentName(invoice);
            String zipName = DianAcquirerMailComposer.zipAttachmentName(attachmentDownload.fileName(), invoice);

            return dispatch(
                    sender,
                    from,
                    toEmail,
                    subject,
                    body,
                    pdfBytes,
                    pdfName,
                    attachmentDownload.bytes(),
                    zipName
            );
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("mail.dispatch failed invoice_id={} to={}: {}", invoiceId, toEmail, ex.getMessage());
            throw new IllegalStateException("No fue posible enviar el correo: " + ex.getMessage(), ex);
        }
    }

    /** Reenvío de documentos de recepción (tabla received_invoices, sin tocar emisión). */
    public String sendReceivedDocuments(
            String tenantId,
            UUID receivedId,
            String invoiceNumber,
            String toEmail,
            byte[] pdfBytes,
            byte[] xmlBytes
    ) {
        if (!StringUtils.hasText(toEmail)) {
            throw new IllegalArgumentException("El correo destinatario es obligatorio");
        }
        SociedadMailAccount account = null;
        if (StringUtils.hasText(tenantId)) {
            account = sociedadMailAccountRepository.findBySociedadId(UUID.fromString(tenantId)).orElse(null);
        }
        JavaMailSender sender;
        String from;
        if (account != null && account.hasOutgoingMail()) {
            sender = createSender(account);
            from = firstNonBlank(account.correoEmision(), account.usuarioSmtp(), mailProperties.getFrom());
        } else if (mailProperties.isDispatchEnabled()) {
            sender = fallbackMailSender;
            from = mailProperties.getFrom();
        } else {
            throw new IllegalStateException(
                    "El correo saliente de la sociedad no está configurado."
            );
        }
        try {
            return dispatch(
                    sender,
                    from,
                    toEmail,
                    "Documentos electrónicos Zona K - " + (StringUtils.hasText(invoiceNumber) ? invoiceNumber : receivedId),
                    "Adjunto encontrará los documentos electrónicos recibidos.",
                    pdfBytes,
                    "factura-" + receivedId + ".pdf",
                    null,
                    null,
                    xmlBytes,
                    "factura-" + receivedId + ".xml"
            );
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible enviar el correo: " + ex.getMessage(), ex);
        }
    }

    private String dispatch(
            JavaMailSender sender,
            String from,
            String toEmail,
            String subject,
            String body,
            byte[] pdfBytes,
            String pdfFileName,
            byte[] zipBytes,
            String zipFileName
    ) throws Exception {
        return dispatch(sender, from, toEmail, subject, body, pdfBytes, pdfFileName, zipBytes, zipFileName, null, null);
    }

    private String dispatch(
            JavaMailSender sender,
            String from,
            String toEmail,
            String subject,
            String body,
            byte[] pdfBytes,
            String pdfFileName,
            byte[] zipBytes,
            String zipFileName,
            byte[] xmlBytes,
            String xmlFileName
    ) throws Exception {
        MimeMessage mimeMessage = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
        helper.setFrom(from);
        helper.setTo(toEmail.trim());
        helper.setSubject(subject);
        helper.setText(body == null ? "" : body, false);
        if (pdfBytes != null && pdfBytes.length > 0) {
            helper.addAttachment(pdfFileName, new ByteArrayResource(pdfBytes));
        }
        if (zipBytes != null && zipBytes.length > 0) {
            helper.addAttachment(zipFileName, new ByteArrayResource(zipBytes));
        }
        if (xmlBytes != null && xmlBytes.length > 0) {
            helper.addAttachment(xmlFileName, new ByteArrayResource(xmlBytes));
        }
        sender.send(mimeMessage);
        return "Documentos enviados a " + toEmail.trim();
    }

    private JavaMailSender createSender(SociedadMailAccount account) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(account.hostSmtp());
        sender.setPort(account.puertoSmtp());
        sender.setUsername(account.usuarioSmtp());
        sender.setPassword(account.passwordSmtp());

        Properties mailProps = sender.getJavaMailProperties();
        mailProps.put("mail.transport.protocol", "smtp");
        mailProps.put("mail.smtp.auth", "true");
        mailProps.put("mail.smtp.connectiontimeout", "15000");
        mailProps.put("mail.smtp.timeout", "20000");
        if (account.puertoSmtp() != null && account.puertoSmtp() == 465) {
            mailProps.put("mail.smtp.ssl.enable", "true");
            mailProps.put("mail.smtp.ssl.trust", "*");
        } else {
            mailProps.put("mail.smtp.starttls.enable", "true");
            mailProps.put("mail.smtp.starttls.required", "false");
        }
        return sender;
    }

    private AttachmentDownload downloadAttachmentQuietly(UUID invoiceId, String tenantId, String emissionPointId) {
        try {
            ResponseEntity<byte[]> response = invoiceClientService
                    .downloadInvoiceDocument(invoiceId, "attachment", tenantId, emissionPointId)
                    .block();
            if (response == null || response.getBody() == null || response.getBody().length == 0) {
                return AttachmentDownload.empty();
            }
            String fileName = Optional.ofNullable(response.getHeaders().getContentDisposition())
                    .map(contentDisposition -> contentDisposition.getFilename())
                    .filter(StringUtils::hasText)
                    .orElse(null);
            return new AttachmentDownload(response.getBody(), fileName);
        } catch (Exception ex) {
            log.warn("No se pudo adjuntar ZIP DIAN invoice_id={}: {}", invoiceId, ex.getMessage());
            return AttachmentDownload.empty();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "no-reply@zonak.local";
    }

    /** Notificación textual al proveedor tras evento RADIAN. */
    public String sendPlainText(String tenantId, String toEmail, String subject, String body) {
        if (!StringUtils.hasText(toEmail)) {
            throw new IllegalArgumentException("El correo destinatario es obligatorio");
        }
        SociedadMailAccount account = null;
        if (StringUtils.hasText(tenantId)) {
            account = sociedadMailAccountRepository.findBySociedadId(UUID.fromString(tenantId)).orElse(null);
        }
        JavaMailSender sender;
        String from;
        if (account != null && account.hasOutgoingMail()) {
            sender = createSender(account);
            from = firstNonBlank(account.correoEmision(), account.usuarioSmtp(), mailProperties.getFrom());
        } else if (mailProperties.isDispatchEnabled()) {
            sender = fallbackMailSender;
            from = mailProperties.getFrom();
        } else {
            throw new IllegalStateException(
                    "El correo saliente de la sociedad no está configurado."
            );
        }
        try {
            MimeMessage mimeMessage = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(toEmail.trim());
            helper.setSubject(subject);
            helper.setText(body == null ? "" : body, false);
            sender.send(mimeMessage);
            return "Notificación enviada a " + toEmail.trim();
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible notificar al proveedor: " + ex.getMessage(), ex);
        }
    }

    private record AttachmentDownload(byte[] bytes, String fileName) {
        static AttachmentDownload empty() {
            return new AttachmentDownload(null, null);
        }
    }
}
