package com.zonak.portal.recepcion;

public enum RecepcionEstadoDian {
    PENDIENTE,
    ACUSADA_085,
    RECIBIDA_086,
    ACEPTADA_087,
    RECHAZADA_088;

    public static RecepcionEstadoDian fromDb(String value) {
        if (value == null || value.isBlank()) {
            return PENDIENTE;
        }
        return switch (value.trim().toUpperCase()) {
            case "ACUSADA_085", "085" -> ACUSADA_085;
            case "RECIBIDA_086", "086" -> RECIBIDA_086;
            case "ACEPTADA_087", "087" -> ACEPTADA_087;
            case "RECHAZADA_088", "088", "RECHAZADO" -> RECHAZADA_088;
            case "PENDIENTE", "RECIBIDO_PND" -> PENDIENTE;
            default -> PENDIENTE;
        };
    }
}
