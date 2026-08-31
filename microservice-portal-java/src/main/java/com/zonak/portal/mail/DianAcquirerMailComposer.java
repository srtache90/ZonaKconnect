package com.zonak.portal.mail;

import com.zonak.portal.dto.DianFiscalContext;
import com.zonak.portal.dto.InvoicePdfData;
import org.springframework.util.StringUtils;

/**
 * Asunto y cuerpo de entrega al adquirente (Anexo Técnico FE v1.9 §9.1, Res. 000165 Art. 35).
 */
final class DianAcquirerMailComposer {
    private DianAcquirerMailComposer() {
    }

    static String buildSubject(InvoicePdfData invoice, String businessLine) {
        String nit = digitsOnly(invoice.company().nit());
        String documentId = invoice.documentNumber();
        String typeCode = resolveDocumentTypeCode(invoice.fiscalContext());
        String commercialName = firstNonBlank(invoice.company().razonSocial(), "Emisor");
        StringBuilder subject = new StringBuilder()
                .append(nit).append(';')
                .append(invoice.company().razonSocial()).append(';')
                .append(documentId).append(';')
                .append(typeCode).append(';')
                .append(commercialName);
        if (StringUtils.hasText(businessLine)) {
            subject.append(';').append(businessLine.trim());
        }
        return subject.toString();
    }

    static String buildBody(InvoicePdfData invoice, String receptionEmail) {
        StringBuilder body = new StringBuilder();
        if (StringUtils.hasText(receptionEmail)) {
            body.append("Correo autorrespuesta: ").append(receptionEmail.trim()).append("\n\n");
        }
        body.append("Adjunto encontrará el contenedor electrónico (AttachedDocument) y la representación gráfica del documento ")
                .append(invoice.documentNumber())
                .append(" validado por la DIAN.");
        return body.toString();
    }

    static String pdfAttachmentName(InvoicePdfData invoice) {
        return sanitizeFileName(invoice.documentNumber()) + ".pdf";
    }

    static String zipAttachmentName(String zipFileName, InvoicePdfData invoice) {
        if (StringUtils.hasText(zipFileName)) {
            return sanitizeFileName(zipFileName);
        }
        return "ad-" + sanitizeFileName(invoice.documentNumber()) + ".zip";
    }

    static String resolveDocumentTypeCode(DianFiscalContext fiscalContext) {
        if (fiscalContext == null) {
            return "01";
        }
        return switch (fiscalContext.documentKind()) {
            case CREDIT_NOTE -> "91";
            case DEBIT_NOTE -> "92";
            case INVOICE -> "01";
        };
    }

    private static String digitsOnly(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        StringBuilder digits = new StringBuilder();
        for (char ch : value.toCharArray()) {
            if (Character.isDigit(ch)) {
                digits.append(ch);
            }
        }
        return digits.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static String sanitizeFileName(String value) {
        if (!StringUtils.hasText(value)) {
            return "documento";
        }
        return value.trim().replaceAll("[\\\\/:*?\"<>|]", "-");
    }
}
