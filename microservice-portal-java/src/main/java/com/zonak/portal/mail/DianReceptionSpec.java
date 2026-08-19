package com.zonak.portal.mail;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * Nomenclatura y correo de recepción según Anexo Técnico FE v1.9 (Res. 000165):
 * 6.5.7 XML, 6.5.8 ZIP, 9.1 FE/NC/ND, 9.2 eventos.
 */
final class DianReceptionSpec {
    static final int MAX_MAIL_ZIP_BYTES = 2 * 1024 * 1024;

    /**
     * z + NIT(10) + PT(3) + año(2) + consecutivo hex(8) + .zip
     * Ejemplo anexo: Z08001972680001900000011.zip
     */
    private static final Pattern DIAN_ZIP_NAME = Pattern.compile(
            "^z[0-9]{10}[0-9]{3}[0-9]{2}[0-9a-f]{8}\\.zip$",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * fv|nc|nd|ad|ar + misma cola de 23 caracteres + .xml
     * Ejemplo: fv08001972680001900000011.xml
     */
    private static final Pattern DIAN_XML_NAME = Pattern.compile(
            "^(fv|nc|nd|ad|ar)[0-9]{10}[0-9]{3}[0-9]{2}[0-9a-f]{8}\\.xml$",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 9.1: NIT;Nombre;cbc:ID;InvoiceTypeCode|NC|ND;Nombre comercial;[línea]
     * Ejemplo anexo: 99998888; Facturador Ejemplo; FEV500;01; Facturador Ejemplo;ContabilidadBog
     */
    private static final Pattern INVOICE_SUBJECT = Pattern.compile(
            "^\\s*\\d{5,15}\\s*;\\s*[^;]+\\s*;\\s*[^;]+\\s*;\\s*(0[1-5]|9[12])\\b.*"
    );

    /** 9.2: Evento; número referenciado; NIT; nombre; ID evento; código; [línea] */
    private static final Pattern EVENT_SUBJECT = Pattern.compile(
            "^\\s*evento\\s*;.+",
            Pattern.CASE_INSENSITIVE
    );

    private DianReceptionSpec() {
    }

    static String baseName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "";
        }
        String name = fileName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.trim();
    }

    static boolean isDianZipName(String fileName) {
        String name = baseName(fileName).toLowerCase(Locale.ROOT);
        return DIAN_ZIP_NAME.matcher(name).matches();
    }

    static boolean isDianXmlName(String fileName) {
        String name = baseName(fileName).toLowerCase(Locale.ROOT);
        return DIAN_XML_NAME.matcher(name).matches();
    }

    static boolean isInvoiceReceptionSubject(String subject) {
        return StringUtils.hasText(subject) && INVOICE_SUBJECT.matcher(subject).matches();
    }

    static boolean isEventReceptionSubject(String subject) {
        return StringUtils.hasText(subject) && EVENT_SUBJECT.matcher(subject).matches();
    }

    static boolean isReceptionMail(String subject) {
        return isInvoiceReceptionSubject(subject) || isEventReceptionSubject(subject);
    }

    static boolean isReceivableUbl(String xml) {
        if (!StringUtils.hasText(xml)) {
            return false;
        }
        String trimmed = xml.trim();
        if (trimmed.charAt(0) == '\uFEFF') {
            trimmed = trimmed.substring(1).trim();
        }
        if (!trimmed.startsWith("<")) {
            return false;
        }
        return containsRoot(trimmed, "AttachedDocument")
                || containsRoot(trimmed, "Invoice")
                || containsRoot(trimmed, "CreditNote")
                || containsRoot(trimmed, "DebitNote");
    }

    private static boolean containsRoot(String xml, String localName) {
        String lower = xml.substring(0, Math.min(xml.length(), 800)).toLowerCase(Locale.ROOT);
        String needle = localName.toLowerCase(Locale.ROOT);
        return lower.contains("<" + needle)
                || lower.contains(":" + needle);
    }
}
