package com.zonak.portal.service;

import com.zonak.portal.dto.CreateCreditNoteRequestDTO;
import com.zonak.portal.dto.CreateInvoiceRequestDTO;
import com.zonak.portal.dto.InvoiceListResponseDTO;
import com.zonak.portal.dto.InvoiceResponseDTO;
import com.zonak.portal.exception.InvoiceEmissionException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;

@Service
public class InvoiceClientService {
    private final WebClient coreGoWebClient;

    public InvoiceClientService(@Qualifier("coreGoWebClient") WebClient coreGoWebClient) {
        this.coreGoWebClient = coreGoWebClient;
    }

    public Mono<InvoiceResponseDTO> emitInvoice(CreateInvoiceRequestDTO dto) {
        return coreGoWebClient
                .post()
                .uri("/api/v1/invoices")
                .bodyValue(dto)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> Mono.error(new InvoiceEmissionException(response.statusCode(), body)))
                )
                .bodyToMono(InvoiceResponseDTO.class);
    }

    public Mono<InvoiceResponseDTO> emitInvoice(CreateInvoiceRequestDTO dto, String tenantId, String emissionPointId) {
        return coreGoWebClient
                .post()
                .uri("/api/v1/invoices")
                .header("X-Tenant-ID", tenantId)
                .header("X-Emission-Point-ID", emissionPointId)
                .bodyValue(dto)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> Mono.error(new InvoiceEmissionException(response.statusCode(), body)))
                )
                .bodyToMono(InvoiceResponseDTO.class);
    }

    public Mono<InvoiceResponseDTO> emitCreditNote(
            CreateCreditNoteRequestDTO dto,
            String tenantId,
            String emissionPointId
    ) {
        return coreGoWebClient
                .post()
                .uri("/api/v1/credit-notes")
                .headers(headers -> applyTenantHeaders(headers, tenantId, emissionPointId))
                .bodyValue(dto)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> Mono.error(new InvoiceEmissionException(response.statusCode(), body)))
                )
                .bodyToMono(InvoiceResponseDTO.class);
    }

    public Mono<InvoiceResponseDTO> reemitInvoice(UUID id, String tenantId, String emissionPointId) {
        return coreGoWebClient
                .post()
                .uri("/api/v1/invoices/{id}/reemit", id)
                .headers(headers -> applyTenantHeaders(headers, tenantId, emissionPointId))
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> Mono.error(new InvoiceEmissionException(response.statusCode(), body)))
                )
                .bodyToMono(InvoiceResponseDTO.class);
    }

    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> searchDocuments(String q, String tenantId, String emissionPointId) {
        return coreGoWebClient
                .get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/search").queryParam("q", q == null ? "" : q).build())
                .headers(headers -> applyTenantHeaders(headers, tenantId, emissionPointId))
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> Mono.error(new InvoiceEmissionException(response.statusCode(), body)))
                )
                .bodyToMono(Map.class)
                .map(map -> (Map<String, Object>) map);
    }

    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> dashboardKpis(String tenantId, String emissionPointId) {
        return coreGoWebClient
                .get()
                .uri("/api/v1/dashboard/kpis")
                .headers(headers -> applyTenantHeaders(headers, tenantId, emissionPointId))
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> Mono.error(new InvoiceEmissionException(response.statusCode(), body)))
                )
                .bodyToMono(Map.class)
                .map(map -> (Map<String, Object>) map);
    }

    public Mono<InvoiceResponseDTO> emitSupportDocument(
            Map<String, Object> payload,
            String tenantId,
            String emissionPointId
    ) {
        return coreGoWebClient
                .post()
                .uri("/api/v1/support-documents")
                .headers(headers -> applyTenantHeaders(headers, tenantId, emissionPointId))
                .bodyValue(payload)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> Mono.error(new InvoiceEmissionException(response.statusCode(), body)))
                )
                .bodyToMono(InvoiceResponseDTO.class);
    }

    public Mono<InvoiceResponseDTO> emitPayroll(
            Map<String, Object> payload,
            String tenantId,
            String emissionPointId
    ) {
        return coreGoWebClient
                .post()
                .uri("/api/v1/payroll")
                .headers(headers -> applyTenantHeaders(headers, tenantId, emissionPointId))
                .bodyValue(payload)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> Mono.error(new InvoiceEmissionException(response.statusCode(), body)))
                )
                .bodyToMono(InvoiceResponseDTO.class);
    }

    public Mono<InvoiceListResponseDTO> getInvoices(
            int page,
            int limit,
            String estado,
            String tipo,
            String tenantId,
            String emissionPointId
    ) {
        return coreGoWebClient
                .get()
                .uri(uriBuilder -> buildInvoicesUri(uriBuilder, page, limit, estado, tipo))
                .headers(headers -> applyTenantHeaders(headers, tenantId, emissionPointId))
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> Mono.error(new InvoiceEmissionException(response.statusCode(), body)))
                )
                .bodyToMono(InvoiceListResponseDTO.class);
    }

    public Mono<Void> updateInvoiceUrls(UUID id, String pdfUrl) {
        return coreGoWebClient
                .patch()
                .uri("/api/v1/invoices/{id}/urls", id)
                .bodyValue(Map.of("pdf_s3_url", pdfUrl))
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> Mono.error(new InvoiceEmissionException(response.statusCode(), body)))
                )
                .bodyToMono(Void.class);
    }

    public Mono<ResponseEntity<byte[]>> downloadInvoiceDocument(
            UUID id,
            String kind,
            String tenantId,
            String emissionPointId
    ) {
        return coreGoWebClient
                .get()
                .uri("/api/v1/invoices/{id}/documents/{kind}", id, kind)
                .headers(headers -> applyTenantHeaders(headers, tenantId, emissionPointId))
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> Mono.error(new InvoiceEmissionException(response.statusCode(), body)))
                )
                .toEntity(byte[].class);
    }

    private java.net.URI buildInvoicesUri(UriBuilder uriBuilder, int page, int limit, String estado, String tipo) {
        UriBuilder builder = uriBuilder
                .path("/api/v1/invoices")
                .queryParam("page", page)
                .queryParam("limit", limit);

        if (StringUtils.hasText(estado)) {
            builder.queryParam("estado", estado);
        }
        if (StringUtils.hasText(tipo)) {
            builder.queryParam("tipo", tipo);
        }

        return builder.build();
    }

    private static void applyTenantHeaders(HttpHeaders headers, String tenantId, String emissionPointId) {
        if (StringUtils.hasText(tenantId)) {
            headers.set("X-Tenant-ID", tenantId);
        }
        if (StringUtils.hasText(emissionPointId)) {
            headers.set("X-Emission-Point-ID", emissionPointId);
        }
    }
}
