package com.zonak.portal.recepcion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * Validaciones estructurales de CUFE/CUDE y NIT receptor antes de persistir o emitir eventos RADIAN.
 */
public final class RecepcionCufeValidator {
    private static final Pattern CUFE_SHA384 = Pattern.compile("^[a-fA-F0-9]{96}$");
    private static final Pattern CUFE_HEX_MIN = Pattern.compile("^[a-fA-F0-9]{64,128}$");

    private RecepcionCufeValidator() {
    }

    public static List<String> validateCufe(String cufe) {
        List<String> issues = new ArrayList<>();
        if (!StringUtils.hasText(cufe)) {
            issues.add("CUFE ausente");
            return issues;
        }
        String value = cufe.trim();
        if (value.contains(" ")) {
            issues.add("CUFE contiene espacios");
        }
        if (!CUFE_HEX_MIN.matcher(value).matches()) {
            issues.add("CUFE con formato inválido (se esperan 64–128 hex)");
        } else if (!CUFE_SHA384.matcher(value).matches()) {
            issues.add("CUFE no tiene longitud SHA-384 (96 hex); verifique el XML");
        }
        return issues;
    }

    public static boolean isStructurallyValid(String cufe) {
        return validateCufe(cufe).isEmpty();
    }

    public static String normalizeNit(String nit) {
        if (!StringUtils.hasText(nit)) {
            return "";
        }
        String digits = nit.replaceAll("[^0-9]", "");
        if (digits.length() > 1) {
            // Conserva dígito de verificación si viene como NIT-DV, pero compara base sin DV solo si > 9.
            return digits;
        }
        return digits;
    }

    public static boolean sameNit(String left, String right) {
        String a = normalizeNit(left);
        String b = normalizeNit(right);
        if (!StringUtils.hasText(a) || !StringUtils.hasText(b)) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        // Compara sin DV cuando longitudes difieren en 1.
        if (a.length() == b.length() + 1 && a.startsWith(b)) {
            return true;
        }
        if (b.length() == a.length() + 1 && b.startsWith(a)) {
            return true;
        }
        return false;
    }

    public static String requireValidCufeOrThrow(String cufe) {
        List<String> issues = validateCufe(cufe);
        if (!issues.isEmpty()) {
            throw new IllegalStateException("Validación CUFE fallida: " + String.join("; ", issues));
        }
        return cufe.trim();
    }

    public static String estadoLabel(RecepcionEstadoDian estado) {
        if (estado == null) {
            return "PENDIENTE";
        }
        return estado.name().toLowerCase(Locale.ROOT);
    }
}
