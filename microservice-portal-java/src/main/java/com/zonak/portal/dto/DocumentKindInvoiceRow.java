package com.zonak.portal.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentKindInvoiceRow(
        UUID id,
        String documento,
        String partyName,
        String partyId,
        BigDecimal total,
        String estadoDian,
        OffsetDateTime createdAt,
        String cufe
) {
}
