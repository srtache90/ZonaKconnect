package com.zonak.portal.admin;

import java.util.regex.Pattern;

public final class DianTestSetId {
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );

    private DianTestSetId() {
    }

    public static boolean requiresTestSet(String ambiente) {
        if (ambiente == null || ambiente.isBlank()) {
            return true;
        }
        String value = ambiente.trim();
        if ("Produccion".equalsIgnoreCase(value) || "Producción".equalsIgnoreCase(value)) {
            return false;
        }
        return !"Mock".equalsIgnoreCase(value);
    }

    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}") && trimmed.length() > 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static String validate(String ambiente, String rawValue) {
        String normalized = normalize(rawValue);
        if (!requiresTestSet(ambiente)) {
            return null;
        }
        if (normalized == null) {
            return "En Habilitación el TestSetId es obligatorio.";
        }
        if (!UUID_PATTERN.matcher(normalized).matches()) {
            return "TestSetId inválido. Use el UUID del set de pruebas del catálogo de habilitación.";
        }
        return null;
    }
}
