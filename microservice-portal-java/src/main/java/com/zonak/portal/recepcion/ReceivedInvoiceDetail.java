package com.zonak.portal.recepcion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ReceivedInvoiceDetail(
        UUID id,
        String proveedorName,
        String proveedorNit,
        String receptorNit,
        String sociedadNit,
        String invoiceNumber,
        String cufe,
        BigDecimal totalAmount,
        String fechaEmision,
        RecepcionEstadoDian estadoDian,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        boolean documentsAvailable,
        List<String> validationIssues,
        boolean cufeValid,
        boolean receptorNitMatch,
        RecepcionPlazoStatus plazoStatus,
        String plazoLabel,
        Integer diasRestantes,
        LocalDate plazoLimite,
        LocalDate recibo086Date,
        List<RecepcionEventTimelineItem> timeline,
        String source,
        String xmlPreview
) {
}
