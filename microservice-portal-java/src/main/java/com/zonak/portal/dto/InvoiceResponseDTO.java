package com.zonak.portal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public record InvoiceResponseDTO(
        @JsonProperty("id") UUID id,
        @JsonProperty("status") String status,
        @JsonProperty("reused") Boolean reused,
        @JsonProperty("prefijo") String prefijo,
        @JsonProperty("numero") Long numero
) {
}
