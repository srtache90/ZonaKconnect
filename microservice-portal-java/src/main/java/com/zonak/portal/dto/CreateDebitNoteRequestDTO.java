package com.zonak.portal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record CreateDebitNoteRequestDTO(
        @JsonProperty("ambiente") String ambiente,
        @JsonProperty("customization_id") String customizationId,
        @JsonProperty("debit_note_type_code") String debitNoteTypeCode,
        @JsonProperty("cliente") CreateInvoiceRequestDTO.CustomerDTO cliente,
        @JsonProperty("factura_referencia") CreateCreditNoteRequestDTO.FacturaReferenciaDTO facturaReferencia,
        @JsonProperty("conceptos_correccion") List<CreateCreditNoteRequestDTO.ConceptoCorreccionDTO> conceptosCorreccion,
        @JsonProperty("items") List<CreateInvoiceRequestDTO.ItemDTO> items,
        @JsonProperty("totals_jsonb") Object totals
) {
}
