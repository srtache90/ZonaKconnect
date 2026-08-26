package com.zonak.portal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record InvoiceListResponseDTO(
        @JsonProperty("page") int page,
        @JsonProperty("limit") int limit,
        @JsonProperty("total_records") long totalRecords,
        @JsonProperty("invoices") List<InvoiceItemDTO> invoices
) {
    public record InvoiceItemDTO(
            @JsonProperty("id") UUID id,
            @JsonProperty("tipo") String tipo,
            @JsonProperty("uuid_cude") String uuidCude,
            @JsonProperty("prefijo") String prefijo,
            @JsonProperty("numero") long numero,
            @JsonProperty("estado_dian") String estadoDian,
            @JsonProperty("xml_s3_url") String xmlS3Url,
            @JsonProperty("pdf_s3_url") String pdfS3Url,
            @JsonProperty("created_at") OffsetDateTime createdAt,
            @JsonProperty("updated_at") OffsetDateTime updatedAt,
            @JsonProperty("dian_error_code") String dianErrorCode,
            @JsonProperty("dian_error_description") String dianErrorDescription,
            @JsonProperty("dian_status_message") String dianStatusMessage,
            @JsonProperty("dian_errores") String dianErrores,
            @JsonProperty("dian_track_id") String dianTrackId,
            @JsonProperty("customer_email") String customerEmail,
            @JsonProperty("document_kind") String documentKind
    ) {
    }
}
