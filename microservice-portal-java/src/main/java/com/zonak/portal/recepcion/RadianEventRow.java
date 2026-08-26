package com.zonak.portal.recepcion;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RadianEventRow(
        UUID id,
        UUID receivedInvoiceId,
        String eventCode,
        String eventAction,
        String eventLabel,
        String cufe,
        String invoiceNumber,
        String supplierName,
        String supplierNit,
        String supplierEmail,
        String estado,
        String trackId,
        String cude,
        String ambiente,
        String notifyStatus,
        String notifyDetail,
        OffsetDateTime notifiedAt,
        OffsetDateTime createdAt
) {
}
