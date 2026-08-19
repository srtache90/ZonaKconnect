package com.zonak.portal.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record SalesDetailReportRow(
        UUID invoiceId,
        String documento,
        OffsetDateTime fechaEmision,
        String nitCliente,
        String nombreCliente,
        String direccionCliente,
        String emailCliente,
        BigDecimal baseGravada,
        BigDecimal baseExenta,
        BigDecimal iva,
        BigDecimal otrosImpuestos,
        BigDecimal propinas,
        BigDecimal subtotal,
        BigDecimal total,
        String estadoDian,
        String cufe
) {
}
