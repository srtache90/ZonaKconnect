package com.zonak.portal.integration.sap;

import java.util.UUID;

public record SapDianStatus(
        UUID invoiceId,
        String prefijo,
        long numero,
        String estadoDian,
        String cufe,
        String mensajeDian
) {
    public String documentNumber() {
        String prefix = prefijo == null ? "" : prefijo.trim();
        return prefix + numero;
    }
}
