package com.zonak.portal.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zonak.portal.auth.ApiKeyAuthenticationFilter;
import com.zonak.portal.auth.ApiTenant;
import com.zonak.portal.auth.ApiTenantResolver;
import com.zonak.portal.dto.CreateCreditNoteRequestDTO;
import com.zonak.portal.dto.CreateInvoiceRequestDTO;
import com.zonak.portal.dto.InvoiceResponseDTO;
import com.zonak.portal.exception.InvoiceEmissionException;
import com.zonak.portal.integration.pos.PosTicketRequest;
import com.zonak.portal.integration.sap.SapConsultaDocumento;
import com.zonak.portal.integration.sap.SapDianStatus;
import com.zonak.portal.service.InvoiceOrchestratorService;
import com.zonak.portal.service.InvoiceReportRepository;
import com.zonak.portal.support.InvoiceDianStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/ingest")
public class PosIntegrationController {
    private static final Logger log = LoggerFactory.getLogger(PosIntegrationController.class);

    private final ObjectMapper objectMapper;
    private final IngestInvoiceMapper ingestInvoiceMapper;
    private final InvoiceOrchestratorService invoiceOrchestratorService;
    private final InvoiceReportRepository invoiceReportRepository;
    private final ApiTenantResolver apiTenantResolver;

    public PosIntegrationController(
            ObjectMapper objectMapper,
            IngestInvoiceMapper ingestInvoiceMapper,
            InvoiceOrchestratorService invoiceOrchestratorService,
            InvoiceReportRepository invoiceReportRepository,
            ApiTenantResolver apiTenantResolver
    ) {
        this.objectMapper = objectMapper;
        this.ingestInvoiceMapper = ingestInvoiceMapper;
        this.invoiceOrchestratorService = invoiceOrchestratorService;
        this.invoiceReportRepository = invoiceReportRepository;
        this.apiTenantResolver = apiTenantResolver;
    }

    @GetMapping(value = "/pos/estado/{documento}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> consultarEstadoPosPorDocumento(
            @PathVariable String documento,
            HttpServletRequest request
    ) {
        return consultarEstadoPos(documento, null, null, null, null, null, null, null, request);
    }

    @GetMapping(value = {"/pos", "/pos/estado"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> consultarEstadoPos(
            @RequestParam(name = "numero_factura", required = false) String numeroFactura,
            @RequestParam(name = "numero_factura_completo", required = false) String numeroFacturaCompleto,
            @RequestParam(name = "prefijo", required = false) String prefijo,
            @RequestParam(name = "numero", required = false) String numero,
            @RequestParam(name = "consecutivo", required = false) String consecutivo,
            @RequestParam(name = "invoice_id", required = false) String invoiceId,
            @RequestParam(name = "check_id", required = false) String checkId,
            @RequestParam(name = "numero_ticket", required = false) String numeroTicket,
            HttpServletRequest request
    ) {
        ApiTenant apiTenant = requireTenant(request);
        String factura = firstText(
                numeroFacturaCompleto,
                numeroFactura,
                queryParam(request, "numero_factura_completo", "numerofacturacompleto", "documento", "factura")
        );
        String prefix = firstText(
                prefijo,
                queryParam(request, "prefijo"),
                leadingLetters(factura)
        );
        Long consecutivoDocumento = parseLong(firstText(
                numero,
                consecutivo,
                queryParam(request, "numero", "consecutivo")
        ));
        if (consecutivoDocumento == null) {
            consecutivoDocumento = trailingLong(factura);
        }
        String invoice = firstText(invoiceId, queryParam(request, "invoice_id", "invoiceid", "id"));
        String check = firstText(checkId, queryParam(request, "check_id", "checkid"));
        String ticket = firstText(numeroTicket, queryParam(request, "numero_ticket", "numeroticket"));
        boolean consulta = request.getRequestURI().contains("/estado")
                || StringUtils.hasText(invoice)
                || StringUtils.hasText(factura)
                || consecutivoDocumento != null
                || StringUtils.hasText(check)
                || StringUtils.hasText(ticket);
        if (!consulta) {
            return ResponseEntity.ok(Map.of(
                    "status", "POS_INGEST_OK",
                    "tenantId", apiTenant.tenantId(),
                    "emissionPointId", apiTenant.emissionPointId(),
                    "sociedad", apiTenant.razonSocial(),
                    "uso", "GET /api/v1/ingest/pos/estado?numero_factura=SETP990000029 con header X-API-Key"
            ));
        }
        if (consecutivoDocumento == null
                && !StringUtils.hasText(invoice)
                && !StringUtils.hasText(check)
                && !StringUtils.hasText(ticket)
                && !StringUtils.hasText(factura)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "pendiente", false,
                    "error", "Indique numero_factura, prefijo y numero, invoice_id, check_id o numero_ticket"
            ));
        }
        try {
            Optional<SapDianStatus> documento = buscarEstado(
                    apiTenant,
                    invoice,
                    prefix,
                    consecutivoDocumento,
                    factura,
                    check,
                    ticket
            );
            String prefijoRespuesta = prefix;
            Long numeroRespuesta = consecutivoDocumento;
            String facturaRespuesta = factura;
            return documento
                    .map(status -> ResponseEntity.ok(estadoBody(status)))
                    .orElseGet(() -> ResponseEntity.ok(noEncontrado(prefijoRespuesta, numeroRespuesta, facturaRespuesta)));
        } catch (IllegalArgumentException ex) {
            HttpStatus status = ex.getMessage() != null && ex.getMessage().contains("varios documentos")
                    ? HttpStatus.CONFLICT
                    : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(Map.of("error", ex.getMessage()));
        }
    }

    private Optional<SapDianStatus> buscarEstado(
            ApiTenant apiTenant,
            String invoiceId,
            String prefijo,
            Long numero,
            String numeroFactura,
            String checkId,
            String numeroTicket
    ) {
        if (StringUtils.hasText(invoiceId)) {
            UUID id = UUID.fromString(invoiceId.trim());
            return invoiceReportRepository.findSapDianStatus(apiTenant.tenantId(), id);
        }
        if (numero != null) {
            return invoiceReportRepository.findSapDocumento(apiTenant.tenantId(), prefijo, numero)
                    .map(PosIntegrationController::toStatus);
        }
        return invoiceReportRepository.findPosEstadoByReferencia(
                apiTenant.tenantId(),
                checkId,
                numeroTicket,
                numeroFactura
        );
    }

    private static SapDianStatus toStatus(SapConsultaDocumento documento) {
        return new SapDianStatus(
                documento.invoiceId(),
                documento.prefijo(),
                documento.numero(),
                documento.estadoDian(),
                documento.cufe(),
                documento.mensajeDian()
        );
    }

    private static Map<String, Object> estadoBody(SapDianStatus documento) {
        boolean aprobada = InvoiceDianStatus.isValidated(documento.estadoDian(), documento.cufe());
        boolean rechazada = InvoiceDianStatus.isRejected(documento.estadoDian());
        boolean pendiente = !aprobada && !rechazada;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", aprobada ? "APROBADA" : rechazada ? "RECHAZADA" : "PENDIENTE");
        body.put("encontrado", true);
        body.put("aprobada_dian", aprobada);
        body.put("rechazada_dian", rechazada);
        body.put("pendiente", pendiente);
        body.put("estado_dian", documento.estadoDian() == null ? "" : documento.estadoDian());
        body.put("cufe", documento.cufe() == null ? "" : documento.cufe());
        body.put("mensaje", documento.mensajeDian() == null ? "" : documento.mensajeDian());
        body.put("prefijo", documento.prefijo() == null ? "" : documento.prefijo());
        body.put("numero", documento.numero());
        body.put("invoice_id", documento.invoiceId());
        body.put("numero_factura", documento.documentNumber());
        return body;
    }

    private static Map<String, Object> noEncontrado(String prefijo, Long numero, String numeroFactura) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "NO_ENCONTRADO");
        body.put("encontrado", false);
        body.put("aprobada_dian", false);
        body.put("rechazada_dian", false);
        body.put("pendiente", false);
        body.put("estado_dian", "NO_ENCONTRADO");
        body.put("cufe", "");
        body.put("mensaje", "Documento no encontrado para la sociedad de la API key");
        body.put("prefijo", prefijo == null ? "" : prefijo);
        body.put("numero", numero);
        body.put("numero_factura", StringUtils.hasText(numeroFactura) ? numeroFactura.trim() : "");
        return body;
    }

    private static String queryParam(HttpServletRequest request, String... names) {
        if (request == null || names == null) {
            return null;
        }
        for (String name : names) {
            String direct = request.getParameter(name);
            if (StringUtils.hasText(direct)) {
                return direct.trim();
            }
        }
        var parameters = request.getParameterMap();
        for (var entry : parameters.entrySet()) {
            if (entry.getValue() == null || entry.getValue().length == 0 || !StringUtils.hasText(entry.getValue()[0])) {
                continue;
            }
            String key = entry.getKey() == null ? "" : entry.getKey().replace("_", "");
            for (String name : names) {
                if (key.equalsIgnoreCase(name.replace("_", ""))) {
                    return entry.getValue()[0].trim();
                }
            }
        }
        return null;
    }

    private static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static Long parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String leadingLetters(String documentNumber) {
        if (!StringUtils.hasText(documentNumber)) {
            return null;
        }
        StringBuilder letters = new StringBuilder();
        for (char ch : documentNumber.trim().toCharArray()) {
            if (Character.isLetter(ch)) {
                letters.append(ch);
            } else if (letters.length() > 0) {
                break;
            }
        }
        return letters.length() == 0 ? null : letters.toString();
    }

    private static Long trailingLong(String documentNumber) {
        if (!StringUtils.hasText(documentNumber)) {
            return null;
        }
        StringBuilder digits = new StringBuilder();
        for (char ch : documentNumber.trim().toCharArray()) {
            if (Character.isDigit(ch)) {
                digits.append(ch);
            } else if (digits.length() > 0) {
                break;
            }
        }
        if (digits.length() == 0) {
            return null;
        }
        try {
            return Long.parseLong(digits.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @PostMapping(
            value = "/pos",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Mono<ResponseEntity<Map<String, Object>>> ingestPosJson(
            @RequestBody JsonNode body,
            HttpServletRequest request
    ) {
        return emitPos(readTicket(body), body, requireTenant(request));
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
                .flatMap(payload -> emitPos(payload.ticket(), payload.body(), apiTenant));
    }

    private Mono<ResponseEntity<Map<String, Object>>> emitPos(
            PosTicketRequest ticket,
            JsonNode source,
            ApiTenant apiTenant
    ) {
        ApiTenant routed;
        try {
            routed = routeEmissionPoint(apiTenant, ticket);
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        }
        if (ticket != null && ticket.isCreditNote()) {
            return Mono.fromCallable(() -> ingestInvoiceMapper.withSimphonyJson(
                            ingestInvoiceMapper.fromPosCreditNote(ticket),
                            source
                    ))
                    .flatMap(dto -> emitCreditNote(dto, routed))
                    .map(invoiceId -> ResponseEntity.accepted().body(responseBody(routed, ticket, invoiceId)))
                .onErrorResume(IllegalArgumentException.class, ex -> badRequest(ex.getMessage()))
                .onErrorResume(InvoiceEmissionException.class, this::emissionError);
        }
        return Mono.fromCallable(() -> ingestInvoiceMapper.withSimphonyJson(
                        ingestInvoiceMapper.fromPos(ticket),
                        source
                ))
                .flatMap(dto -> emitResult(dto, routed))
                .map(response -> ResponseEntity.accepted().body(responseBody(routed, ticket, response)))
                .onErrorResume(IllegalArgumentException.class, ex -> badRequest(ex.getMessage()))
                .onErrorResume(InvoiceEmissionException.class, this::emissionError);
    }

    private Map<String, Object> responseBody(ApiTenant apiTenant, PosTicketRequest ticket, UUID invoiceId) {
        return responseBody(apiTenant, ticket, invoiceId, null, ticket.resolvedPrefijo(), ticket.resolvedNumero());
    }

    private Map<String, Object> responseBody(
            ApiTenant apiTenant,
            PosTicketRequest ticket,
            InvoiceResponseDTO response
    ) {
        UUID invoiceId = response != null ? response.id() : null;
        Boolean reused = response != null ? response.reused() : null;
        String prefijo = response != null && StringUtils.hasText(response.prefijo())
                ? response.prefijo()
                : ticket.resolvedPrefijo();
        Long numero = response != null && response.numero() != null
                ? response.numero()
                : ticket.resolvedNumero();
        return responseBody(apiTenant, ticket, invoiceId, reused, prefijo, numero);
    }

    private Map<String, Object> responseBody(
            ApiTenant apiTenant,
            PosTicketRequest ticket,
            UUID invoiceId,
            Boolean reused,
            String prefijo,
            Long numero
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", Boolean.TRUE.equals(reused) ? "POS_REUTILIZADA_EN_CORRECCION" : "POS_RECIBIDO_EN_PROCESAMIENTO");
        body.put("tenantId", apiTenant.tenantId());
        body.put("emissionPointId", apiTenant.emissionPointId());
        body.put("tipoDocumento", ticket.isCreditNote() ? "NC" : "FV");
        body.put("invoiceId", invoiceId);
        body.put("numeroTicket", ticket.numeroTicket());
        body.put("checkId", ticket.checkId());
        body.put("cajaWsid", ticket.cajaWsid());
        body.put("resolucion", ticket.resolucion());
        body.put("prefijo", prefijo);
        body.put("numero", numero);
        body.put("ambiente", ticket.resolvedAmbiente());
        body.put("reused", Boolean.TRUE.equals(reused));
        return body;
    }

    private ApiTenant routeEmissionPoint(ApiTenant apiTenant, PosTicketRequest ticket) {
        if (ticket == null) {
            return apiTenant;
        }
        if (StringUtils.hasText(ticket.resolucion())) {
            try {
                UUID pointId = apiTenantResolver.requirePointByResolucion(
                        apiTenant.tenantId(),
                        ticket.resolucion(),
                        ticket.resolvedPrefijo(),
                        ticket.referenciaNumero()
                );
                return new ApiTenant(apiTenant.tenantId(), pointId, apiTenant.razonSocial());
            } catch (IllegalArgumentException resolutionError) {
                if (StringUtils.hasText(ticket.cajaWsid())) {
                    return apiTenantResolver
                            .findActivePointByCodigo(apiTenant.tenantId(), ticket.cajaWsid())
                            .map(pointId -> new ApiTenant(apiTenant.tenantId(), pointId, apiTenant.razonSocial()))
                            .orElseThrow(() -> new IllegalArgumentException(
                                    resolutionError.getMessage()
                                            + " Tampoco hay PV con codigo/caja_wsid " + ticket.cajaWsid()
                                            + " en " + apiTenant.razonSocial() + "."
                            ));
                }
                throw new IllegalArgumentException(
                        resolutionError.getMessage()
                                + " Sociedad=" + apiTenant.razonSocial()
                                + ". Cree el PV con esa resolucion o envie la Resolucion del PV existente."
                );
            }
        }
        if (StringUtils.hasText(ticket.cajaWsid())) {
            return apiTenantResolver
                    .findActivePointByCodigo(apiTenant.tenantId(), ticket.cajaWsid())
                    .map(pointId -> new ApiTenant(apiTenant.tenantId(), pointId, apiTenant.razonSocial()))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No hay punto de venta con codigo/caja_wsid " + ticket.cajaWsid()
                                    + " en " + apiTenant.razonSocial() + " y el JSON no trae Resolucion."
                    ));
        }
        return apiTenant;
    }

    private PosPayload parseFile(MultipartFile archivo) {
        if (archivo == null || archivo.isEmpty()) {
            throw new IllegalArgumentException("Debe adjuntar un archivo JSON POS (campo archivo).");
        }
        try {
            JsonNode body = objectMapper.readTree(archivo.getBytes());
            return new PosPayload(body, readTicket(body));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("JSON POS inválido: " + ex.getMessage());
        }
    }

    private PosTicketRequest readTicket(JsonNode body) {
        if (body == null || body.isNull() || !body.isObject()) {
            throw new IllegalArgumentException("JSON POS vacío");
        }
        try {
            return objectMapper.treeToValue(body, PosTicketRequest.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("JSON POS inválido: " + ex.getMessage());
        }
    }

    private record PosPayload(JsonNode body, PosTicketRequest ticket) {
    }

    private Mono<UUID> emitCreditNote(CreateCreditNoteRequestDTO dto, ApiTenant apiTenant) {
        return invoiceOrchestratorService.processAndPersistCreditNote(
                dto,
                apiTenant.tenantId().toString(),
                apiTenant.emissionPointId().toString()
        );
    }

    private Mono<InvoiceResponseDTO> emitResult(CreateInvoiceRequestDTO dto, ApiTenant apiTenant) {
        return invoiceOrchestratorService.processAndPersistInvoiceResult(
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
        log.warn("Ingesta POS 400: {}", message);
        return Mono.just(ResponseEntity.badRequest().body(Map.of("error", message)));
    }

    private Mono<ResponseEntity<Map<String, Object>>> emissionError(InvoiceEmissionException ex) {
        if (ex.statusCode() != null && ex.statusCode().value() == 409) {
            return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage())));
        }
        if (ex.statusCode() != null && ex.statusCode().is4xxClientError()) {
            return badRequest(ex.getMessage());
        }
        return badGateway(ex.getMessage());
    }

    private Mono<ResponseEntity<Map<String, Object>>> badGateway(String message) {
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", message)));
    }
}
