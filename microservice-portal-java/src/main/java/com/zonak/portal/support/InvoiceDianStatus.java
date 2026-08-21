package com.zonak.portal.support;

public final class InvoiceDianStatus {
    private InvoiceDianStatus() {
    }

    public static boolean isValidated(String estadoDian, String uuidCude) {
        if (uuidCude == null || uuidCude.isBlank()) {
            return false;
        }

        if (estadoDian == null || estadoDian.isBlank()) {
            return false;
        }

        String normalized = estadoDian.trim();
        if ("ENVIADO".equalsIgnoreCase(normalized)) {
            return true;
        }

        String lower = normalized.toLowerCase();
        return (lower.contains("validado") || lower.contains("exitosamente"))
                && !lower.contains("rechaz");
    }

    public static boolean isRejected(String estadoDian) {
        if (estadoDian == null || estadoDian.isBlank()) {
            return false;
        }
        String normalized = estadoDian.trim().toUpperCase();
        if ("RECHAZADO_DIAN".equals(normalized) || "ERROR_DIAN_NET".equals(normalized) || "RECHAZADO".equals(normalized)) {
            return true;
        }
        String lower = estadoDian.toLowerCase();
        return lower.contains("rechaz") || lower.contains("fallid");
    }

    public static boolean isPending(String estadoDian) {
        if (estadoDian == null || estadoDian.isBlank()) {
            return true;
        }
        String normalized = estadoDian.trim().toUpperCase();
        return "PENDIENTE".equals(normalized)
                || "PENDIENTE_NC".equals(normalized)
                || "PENDIENTE_ND".equals(normalized)
                || "EN_REINTENTO".equals(normalized);
    }
}
