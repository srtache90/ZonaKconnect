package com.zonak.portal.recepcion;

public enum RecepcionEstadoDian {
    PENDIENTE,
    ACUSADA_085,
    RECIBIDA_086,
    ACEPTADA_087,
    ACEPTADA_TACITA,
    RECHAZADA_088;

    public static RecepcionEstadoDian fromDb(String value) {
        if (value == null || value.isBlank()) {
            return PENDIENTE;
        }
        return switch (value.trim().toUpperCase()) {
            case "ACUSADA_085", "085" -> ACUSADA_085;
            case "RECIBIDA_086", "086" -> RECIBIDA_086;
            case "ACEPTADA_087", "087" -> ACEPTADA_087;
            case "ACEPTADA_TACITA", "TACITA", "ACEPTACION_TACITA" -> ACEPTADA_TACITA;
            case "RECHAZADA_088", "088", "RECHAZADO" -> RECHAZADA_088;
            case "PENDIENTE", "RECIBIDO_PND" -> PENDIENTE;
            default -> PENDIENTE;
        };
    }

    public boolean isTerminal() {
        return this == ACEPTADA_087 || this == ACEPTADA_TACITA || this == RECHAZADA_088;
    }

    public boolean awaitsTacitAcceptance() {
        return this == RECIBIDA_086;
    }
}
