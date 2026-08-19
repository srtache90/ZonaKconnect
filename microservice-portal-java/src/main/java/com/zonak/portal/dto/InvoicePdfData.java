package com.zonak.portal.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record InvoicePdfData(
        UUID invoiceId,
        UUID tenantId,
        String documentNumber,
        String prefijo,
        long numero,
        DianFiscalContext fiscalContext,
        Company company,
        Customer customer,
        Resolution resolution,
        List<Item> items,
        List<TaxDetail> impuestos,
        List<TaxDetail> retenciones,
        List<ChargeDetail> recargos,
        Totals totals,
        String status
) {
    public record Company(
            String razonSocial,
            String nit,
            String dv,
            String regimen,
            String direccion,
            String email,
            String telefono,
            String logoDataUri
    ) {
    }

    public record Customer(
            String razonSocial,
            String identificacion,
            String direccion,
            String email
    ) {
    }

    public record Resolution(
            String numeroResolucion,
            String prefijo,
            long rangoDesde,
            long rangoHasta,
            LocalDate fechaDesde,
            LocalDate fechaHasta
    ) {
    }

    public record Item(
            BigDecimal cantidad,
            String descripcion,
            BigDecimal valorUnitario,
            BigDecimal descuento,
            BigDecimal iva,
            BigDecimal otrosImpuestos,
            BigDecimal total
    ) {
    }

    public record Totals(
            BigDecimal subtotal,
            BigDecimal descuentos,
            BigDecimal iva,
            BigDecimal otrosImpuestos,
            BigDecimal totalImpuestos,
            BigDecimal totalRetenciones,
            BigDecimal totalRecargos,
            BigDecimal total
    ) {
    }

    public record TaxDetail(
            String codigo,
            String nombre,
            BigDecimal porcentaje,
            BigDecimal baseImponible,
            BigDecimal valor
    ) {
    }

    public record ChargeDetail(
            String codigo,
            String nombre,
            BigDecimal valor
    ) {
    }
}
