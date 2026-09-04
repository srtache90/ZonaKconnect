package com.zonak.portal.integration.sap;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SapXmlNormalizer {
    private static final Pattern SOAP_BODY = Pattern.compile(
            "<(?:[\\w.-]+:)?Body\\b[^>]*>(.*)</(?:[\\w.-]+:)?Body>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern XMLNS = Pattern.compile("\\s+xmlns(?::[\\w.-]+)?=\"[^\"]*\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFIXED_TAG = Pattern.compile("<(/?)[\\w.-]+:");

    private SapXmlNormalizer() {
    }

    static String unwrap(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("XML SAP vacío");
        }
        String trimmed = stripBom(xml.strip());
        Matcher body = SOAP_BODY.matcher(trimmed);
        if (body.find()) {
            trimmed = body.group(1).strip();
        }
        String withoutNamespaces = XMLNS.matcher(trimmed).replaceAll("");
        return PREFIXED_TAG.matcher(withoutNamespaces).replaceAll("<$1");
    }

    private static String stripBom(String value) {
        if (!value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }
}
