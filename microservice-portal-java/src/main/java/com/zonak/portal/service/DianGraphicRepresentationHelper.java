package com.zonak.portal.service;

import com.zonak.portal.dto.DianFiscalContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DianGraphicRepresentationHelper {
    private static final Map<String, String> FORMA_PAGO = Map.of(
            "1", "Contado",
            "01", "Contado",
            "2", "Crédito",
            "02", "Crédito"
    );

    private static final Map<String, String> MEDIO_PAGO = Map.ofEntries(
            Map.entry("1", "Instrumento no definido"),
            Map.entry("10", "Efectivo"),
            Map.entry("20", "Cheque"),
            Map.entry("42", "Consignación bancaria"),
            Map.entry("45", "Transferencia débito bancaria"),
            Map.entry("47", "Transferencia crédito bancaria"),
            Map.entry("48", "Tarjeta crédito"),
            Map.entry("49", "Tarjeta débito"),
            Map.entry("71", "Bonos"),
            Map.entry("72", "Vales")
    );

    private static final Map<String, String> DOCUMENT_TYPE = Map.of(
            "01", "Factura electrónica de venta",
            "91", "Nota Crédito",
            "92", "Nota Débito"
    );

    private static final Map<String, String> CUSTOMIZATION = Map.of(
            "20", "Nota crédito que referencia una factura electrónica",
            "22", "Nota crédito sin referencia a facturas",
            "23", "Nota crédito para facturación electrónica V1 (Decreto 2242)",
            "30", "Nota débito que referencia una factura electrónica",
            "32", "Nota débito sin referencia a facturas"
    );

    private static final Map<String, String> REGIMEN = Map.of(
            "O-13", "Gran contribuyente",
            "O-15", "Autorretenedor del Impuesto sobre la Renta y Complementarios",
            "O-23", "Agente de retención del IVA",
            "O-47", "Contribuyente del impuesto unificado bajo el régimen SIMPLE de tributación",
            "ZZ", "No responsable de obligaciones especiales en facturación electrónica"
    );

    private static final Map<String, String> UNIDAD = Map.of(
            "94", "Unidad",
            "NIU", "Número de unidades internacionales",
            "EA", "Elemento",
            "KGM", "Kilogramo",
            "LTR", "Litro",
            "MTR", "Metro"
    );

    private DianGraphicRepresentationHelper() {
    }

    static String documentTitle(DianFiscalContext.DocumentKind kind) {
        return switch (kind) {
            case CREDIT_NOTE -> "Nota crédito de la factura electrónica de venta";
            case DEBIT_NOTE -> "Nota débito de la factura electrónica de venta";
            default -> "Factura electrónica de venta";
        };
    }

    static String formaPagoLabel(String code) {
        if (code == null || code.isBlank()) {
            return "Contado";
        }
        String normalized = code.trim();
        return FORMA_PAGO.getOrDefault(normalized, normalized);
    }

    static String medioPagoLabel(String code) {
        if (code == null || code.isBlank()) {
            return "Efectivo";
        }
        String normalized = code.trim();
        return MEDIO_PAGO.getOrDefault(normalized, "Medio de pago " + normalized);
    }

    static String documentTypeLabel(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        return DOCUMENT_TYPE.getOrDefault(code.trim(), "Documento " + code.trim());
    }

    static String customizationLabel(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        return CUSTOMIZATION.getOrDefault(code.trim(), "Tipo de operación " + code.trim());
    }

    static String unitLabel(String code) {
        if (code == null || code.isBlank()) {
            return "Unidad";
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return UNIDAD.getOrDefault(normalized, normalized);
    }

    static String taxResponsibilities(String regimen) {
        if (regimen == null || regimen.isBlank()) {
            return "";
        }
        List<String> labels = new ArrayList<>();
        Arrays.stream(regimen.split(";"))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(token -> token.toUpperCase(Locale.ROOT))
                .distinct()
                .forEach(token -> labels.add(REGIMEN.getOrDefault(token, token)));
        return String.join(" · ", labels);
    }

    static boolean isConsumidorFinal(String identificacion, String razonSocial) {
        String id = identificacion == null ? "" : identificacion.replaceAll("\\D", "");
        String name = razonSocial == null ? "" : razonSocial.trim().toUpperCase(Locale.ROOT);
        return "222222222222".equals(id)
                || name.contains("CONSUMIDOR FINAL");
    }
}
