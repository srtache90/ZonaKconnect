package com.zonak.portal.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zonak.portal.auth.ApiKeyAuthenticationFilter;
import com.zonak.portal.auth.ApiTenant;
import com.zonak.portal.auth.ApiTenantResolver;
import com.zonak.portal.dto.CreateCreditNoteRequestDTO;
import com.zonak.portal.dto.CreateInvoiceRequestDTO;
import com.zonak.portal.exception.InvoiceEmissionException;
import com.zonak.portal.integration.pos.PosTicketRequest;
import com.zonak.portal.service.InvoiceOrchestratorService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/ingest")
public class PosIntegrationController {
    private final ObjectMapper objectMapper;
    private final IngestInvoiceMapper ingestInvoiceMapper;
    private final InvoiceOrchestratorService invoiceOrchestratorService;
    private final ApiTenantResolver apiTenantResolver;

    public PosIntegrationController(
            ObjectMapper objectMapper,
            IngestInvoiceMapper ingestInvoiceMapper,
            InvoiceOrchestratorService invoiceOrchestratorService,
            ApiTenantResolver apiTenantResolver
    ) {
        this.objectMapper = objectMapper;
        this.ingestInvoiceMapper = ingestInvoiceMapper;
        this.invoiceOrchestratorService = invoiceOrchestratorService;
        this.apiTenantResolver = apiTenantResolver;
    }

    @PostMapping(
            value = "/pos",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Mono<ResponseEntity<Map<String, Object>>> ingestPosJson(
            @RequestBody PosTicketRequest ticket,
            HttpServletRequest request
    ) {
        return emitPos(ticket, requireTenant(request));
    }

    @PostMapping(
            value = "/pos",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Mono<ResponseEntity<Map<String, Object>>> ingestPosFile(
            @RequestPart("archivo") MultipartFile archivo,
            HttpServletRequest request
    ) {
        ApiTenant apiTenant = requireTenant(request);
        return Mono.fromCallable(() -> parseFile(archivo))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(ticket -> emitPos(ticket, apiTenant));
    }

    private Mono<ResponseEntity<Map<String, Object>>> emitPos(PosTicketRequest ticket, ApiTenant apiTenant) {
        ApiTenant routed = routeEmissionPoint(apiTenant, ticket);
        if (ticket != null && ticket.isCreditNote()) {
            return Mono.fromCallable(() -> ingestInvoiceMapper.fromPosCreditNote(ticket))
                    .flatMap(dto -> emitCreditNote(dto, routed))
                    .map(invoiceId -> ResponseEntity.accepted().body(responseBody(routed, ticket, invoiceId)))
                    .onErrorResume(IllegalArgumentException.class, ex -> badRequest(ex.getMessage()))
                    .onErrorResume(InvoiceEmissionException.class, ex -> badGateway(ex.getMessage()));
        }
        return Mono.fromCallable(() -> ingestInvoiceMapper.fromPos(ticket))
                .flatMap(dto -> emit(dto, routed))
                .map(invoiceId -> ResponseEntity.accepted().body(responseBody(routed, ticket, invoiceId)))
                .onErrorResume(IllegalArgumentException.class, ex -> badRequest(ex.getMessage()))
                .onErrorResume(InvoiceEmissionException.class, ex -> badGateway(ex.getMessage()));
    }

    private Map<String, Object> responseBody(ApiTenant apiTenant, PosTicketRequest ticket, UUID invoiceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "POS_RECIBIDO_EN_PROCESAMIENTO");
        body.put("tenantId", apiTenant.tenantId());
        body.put("emissionPointId", apiTenant.emissionPointId());
        body.put("tipoDocumento", ticket.isCreditNote() ? "NC" : "FV");
        body.put("invoiceId", invoiceId);
        body.put("numeroTicket", ticket.numeroTicket());
        body.put("checkId", ticket.checkId());
        body.put("cajaWsid", ticket.cajaWsid());
        body.put("resolucion", ticket.resolucion());
        body.put("prefijo", ticket.prefijo());
        return body;
    }

    private ApiTenant routeEmissionPoint(ApiTenant apiTenant, PosTicketRequest ticket) {
        if (ticket == null) {
            return apiTenant;
        }
        if (StringUtils.hasText(ticket.resolucion())) {
            UUID pointId = apiTenantResolver.requirePointByResolucion(
                    apiTenant.tenantId(),
                    ticket.resolucion(),
                    ticket.prefijo(),
                    ticket.referenciaNumero()
            );
            return new ApiTenant(apiTenant.tenantId(), pointId, apiTenant.razonSocial());
        }
        if (StringUtils.hasText(ticket.cajaWsid())) {
            return apiTenantResolver
                    .findActivePointByCodigo(apiTenant.tenantId(), ticket.cajaWsid())
                    .map(pointId -> new ApiTenant(apiTenant.tenantId(), pointId, apiTenant.razonSocial()))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No hay punto de venta con codigo/caja_wsid " + ticket.cajaWsid()
                                    + " y el JSON no trae Resolucion."
                    ));
        }
        return apiTenant;
    }

    private PosTicketRequest parseFile(MultipartFile archivo) {
        if (archivo == null || archivo.isEmpty()) {
            throw new IllegalArgumentException("Debe adjuntar un archivo JSON POS (campo archivo).");
        }
        try {
            String json = new String(archivo.getBytes(), StandardCharsets.UTF_8);
            return objectMapper.readValue(json, PosTicketRequest.class);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("JSON POS inválido: " + ex.getMessage());
        }
    }

    private Mono<UUID> emitCreditNote(CreateCreditNoteRequestDTO dto, ApiTenant apiTenant) {
        return invoiceOrchestratorService.processAndPersistCreditNote(
                dto,
                apiTenant.tenantId().toString(),
                apiTenant.emissionPointId().toString()
        );
    }

    private Mono<UUID> emit(CreateInvoiceRequestDTO dto, ApiTenant apiTenant) {
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
