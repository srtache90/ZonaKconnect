package com.zonak.portal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record CreateCreditNoteRequestDTO(
        @JsonProperty("ambiente") String ambiente,
        @JsonProperty("customization_id") String customizationId,
        @JsonProperty("credit_note_type_code") String creditNoteTypeCode,
        @JsonProperty("cliente") CreateInvoiceRequestDTO.CustomerDTO cliente,
        @JsonProperty("factura_referencia") FacturaReferenciaDTO facturaReferencia,
        @JsonProperty("conceptos_correccion") List<ConceptoCorreccionDTO> conceptosCorreccion,
        @JsonProperty("items") List<CreateInvoiceRequestDTO.ItemDTO> items,
        @JsonProperty("totals_jsonb") Object totals
) {
    public record FacturaReferenciaDTO(
            @JsonProperty("tipoDocumento") String tipoDocumento,
            @JsonProperty("numeroDocumento") String numeroDocumento,
            @JsonProperty("fechaEmision") String fechaEmision,
            @JsonProperty("cufe") String cufe,
            @JsonProperty("schemeName") String schemeName
    ) {
    }

    public record ConceptoCorreccionDTO(
            @JsonProperty("referenceID") String referenceID,
            @JsonProperty("codigo") String codigo,
            @JsonProperty("descripcion") String descripcion
    ) {
    }
}
