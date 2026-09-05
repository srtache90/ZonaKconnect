package com.zonak.portal.integration.sap;

import java.time.Instant;
import java.util.UUID;

public record SapConsultaDocumento(
        UUID invoiceId,
        String prefijo,
        long numero,
        String estadoDian,
        String cufe,
        String mensajeDian,
        Instant createdAt
) {
}
