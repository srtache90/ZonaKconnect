package com.zonak.portal.reports;

public enum MagneticMediaFormat {
    FORMATO_1007(
            "1007",
            "Ingresos recibidos",
            "Información de ingresos recibidos para terceros (Resolución DIAN medios magnéticos)"
    ),
    FORMATO_1001(
            "1001",
            "Pagos y abonos en cuenta",
            "Pagos o abonos en cuenta y retenciones practicadas (Resolución DIAN medios magnéticos)"
    );

    private final String code;
    private final String title;
    private final String description;

    MagneticMediaFormat(String code, String title, String description) {
        this.code = code;
        this.title = title;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public static MagneticMediaFormat fromCode(String code) {
        if (code == null || code.isBlank()) {
            return FORMATO_1007;
        }
        for (MagneticMediaFormat format : values()) {
            if (format.code.equals(code.trim())) {
                return format;
            }
        }
        throw new IllegalArgumentException("Formato de medios magnéticos no soportado: " + code);
    }
}
