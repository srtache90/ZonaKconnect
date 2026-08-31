package com.zonak.portal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public record CreateInvoiceRequestDTO(
        @JsonProperty("ambiente") String ambiente,
        @JsonProperty("cliente") CustomerDTO customer,
        @JsonProperty("items") List<ItemDTO> items,
        @JsonProperty("xml_base") String xmlBase,
        @JsonProperty("totals_jsonb") Object totals
) {
    public record CustomerDTO(
            @JsonProperty("tipo_identificacion") String identificationType,
            @JsonProperty("numero_identificacion") String identificationNumber,
            @JsonProperty("razon_social") String businessName,
            @JsonProperty("email") String email
    ) {
    }

    public record TaxDTO(
            @JsonProperty("codigo") String code,
            @JsonProperty("nombre") String name,
            @JsonProperty("porcentaje") BigDecimal percentage,
            @JsonProperty("baseImponible") BigDecimal taxableBase,
            @JsonProperty("valor") BigDecimal amount
    ) {
    }

    public record ItemDTO(
            @JsonProperty("codigo") String code,
            @JsonProperty("descripcion") String description,
            @JsonProperty("cantidad") BigDecimal quantity,
            @JsonProperty("precio_unitario") BigDecimal unitPrice,
            @JsonProperty("descuento") BigDecimal discount,
            @JsonProperty("impuestos") List<TaxDTO> taxes
    ) {
    }
}
