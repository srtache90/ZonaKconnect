package com.zonak.portal.integration;

import com.zonak.portal.auth.ApiKeyAuthenticationFilter;
import com.zonak.portal.auth.ApiTenant;
import com.zonak.portal.dto.CreateInvoiceRequestDTO;
import com.zonak.portal.exception.InvoiceEmissionException;
import com.zonak.portal.integration.sap.SapXmlDocumentParser;
import com.zonak.portal.service.InvoiceOrchestratorService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/ingest")
public class SapIntegrationController {
    private final SapXmlDocumentParser sapXmlDocumentParser;
    private final IngestInvoiceMapper ingestInvoiceMapper;
    private final InvoiceOrchestratorService invoiceOrchestratorService;

    public SapIntegrationController(
            SapXmlDocumentParser sapXmlDocumentParser,
            IngestInvoiceMapper ingestInvoiceMapper,
            InvoiceOrchestratorService invoiceOrchestratorService
    ) {
        this.sapXmlDocumentParser = sapXmlDocumentParser;
        this.ingestInvoiceMapper = ingestInvoiceMapper;
        this.invoiceOrchestratorService = invoiceOrchestratorService;
    }

    @PostMapping(
            value = "/sap",
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Mono<ResponseEntity<Map<String, Object>>> ingestSap(
            @RequestBody String xml,
            HttpServletRequest request
    ) {
        ApiTenant apiTenant = requireTenant(request);

        return Mono.fromCallable(() -> sapXmlDocumentParser.parse(xml))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ingestInvoiceMapper::fromSap)
                .flatMap(dto -> emit(dto, apiTenant))
                .map(invoiceId -> ResponseEntity.accepted().body(Map.<String, Object>of(
                        "status", "SAP_RECIBIDO_EN_PROCESAMIENTO",
                        "tenantId", apiTenant.tenantId(),
                        "emissionPointId", apiTenant.emissionPointId(),
                        "invoiceId", invoiceId
                )))
                .onErrorResume(IllegalArgumentException.class, ex -> badRequest(ex.getMessage()))
                .onErrorResume(InvoiceEmissionException.class, ex -> badGateway(ex.getMessage()));
    }

    private Mono<java.util.UUID> emit(CreateInvoiceRequestDTO dto, ApiTenant apiTenant) {
        return invoiceOrchestratorService.processAndPersistInvoice(
                dto,
                apiTenant.tenantId().toString(),
                apiTenant.emissionPointId().toString()
        );
    }

    private ApiTenant requireTenant(HttpServletRequest request) {
        Object value = request.getAttribute(ApiKeyAuthenticationFilter.API_TENANT_ATTRIBUTE);
        if (value instanceof ApiTenant apiTenant) {
            return apiTenant;
        }
        throw new IllegalArgumentException("Tenant API no autenticado");
    }

    private Mono<ResponseEntity<Map<String, Object>>> badRequest(String message) {
        return Mono.just(ResponseEntity.badRequest().body(Map.of("error", message)));
    }

    private Mono<ResponseEntity<Map<String, Object>>> badGateway(String message) {
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", message)));
    }
}
