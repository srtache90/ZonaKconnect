package com.zonak.portal.recepcion;

import java.math.BigDecimal;
import java.util.UUID;

public record ReceivedInvoiceRow(
        UUID id,
        String proveedorName,
        String proveedorNit,
        String invoiceNumber,
        String cufe,
        BigDecimal totalAmount,
        String fechaEmision,
        RecepcionEstadoDian estadoDian,
        String pdfS3Url,
        String xmlS3Url,
        boolean documentsAvailable
) {
}
