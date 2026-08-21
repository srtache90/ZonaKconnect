package com.zonak.portal.recepcion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
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
        boolean documentsAvailable,
        OffsetDateTime createdAt,
        List<String> validationIssues,
        boolean cufeValid,
        RecepcionPlazoStatus plazoStatus,
        String plazoLabel,
        Integer diasRestantes,
        LocalDate plazoLimite,
        UUID assignedEmissionPointId,
        String assignedEmissionPointLabel,
        String assignmentSource
) {
}
