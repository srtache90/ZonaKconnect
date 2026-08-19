package com.zonak.portal.integration.simphony;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SimphonyTicketRequest(
        String ambiente,
        String ticketId,
        Customer customer,
        List<Item> items,
        Totals totals
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Customer(
            String tipoIdentificacion,
            String numeroIdentificacion,
            String razonSocial,
            String email
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            String codigo,
            String descripcion,
            BigDecimal cantidad,
            BigDecimal precioUnitario,
            BigDecimal descuento
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Totals(
            BigDecimal subtotal,
            BigDecimal impuestos,
            BigDecimal total
    ) {
    }
}
