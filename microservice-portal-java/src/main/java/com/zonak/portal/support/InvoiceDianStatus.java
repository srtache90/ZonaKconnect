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
}
