package com.zonak.portal.integration.sap;

import com.zonak.portal.auth.ApiTenant;
import com.zonak.portal.dto.CreateInvoiceRequestDTO;
import com.zonak.portal.exception.InvoiceEmissionException;
import com.zonak.portal.integration.IngestInvoiceMapper;
import com.zonak.portal.service.InvoiceOrchestratorService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SapSoapEndpoint {
    private static final Logger log = LoggerFactory.getLogger(SapSoapEndpoint.class);

    private final SapXmlDocumentParser sapXmlDocumentParser;
    private final SapTenantResolver sapTenantResolver;
    private final IngestInvoiceMapper ingestInvoiceMapper;
    private final InvoiceOrchestratorService invoiceOrchestratorService;
    private final Duration dianWaitTimeout;

    public SapSoapEndpoint(
            SapXmlDocumentParser sapXmlDocumentParser,
            SapTenantResolver sapTenantResolver,
            IngestInvoiceMapper ingestInvoiceMapper,
            InvoiceOrchestratorService invoiceOrchestratorService,
            @Value("${zonak.sap.dian-wait-timeout:120s}") Duration dianWaitTimeout
    ) {
        this.sapXmlDocumentParser = sapXmlDocumentParser;
        this.sapTenantResolver = sapTenantResolver;
        this.ingestInvoiceMapper = ingestInvoiceMapper;
        this.invoiceOrchestratorService = invoiceOrchestratorService;
        this.dianWaitTimeout = dianWaitTimeout;
    }

    @GetMapping(value = {
            "/ws/enviardocumento",
            "/ws/WSEnviarDocumento",
            "/dispapeles/enviardocumento",
            "/WSEnviarDocumento",
            "/WSEnviarDocumento/enviarDocumento"
    }, produces = MediaType.TEXT_XML_VALUE)
    public String ping() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <service name="WSEnviarDocumento">
                  <documentation>Zona K SOAP enviarDocumento. SAP SOAMANAGER debe hacer POST SOAP 1.1 a esta URL.</documentation>
                  <soapAction>enviarDocumento</soapAction>
                </service>
                """;
    }

    @PostMapping(
            value = {
                    "/ws/enviardocumento",
                    "/ws/WSEnviarDocumento",
                    "/dispapeles/enviardocumento",
                    "/WSEnviarDocumento",
                    "/WSEnviarDocumento/enviarDocumento"
            },
            produces = {"text/xml;charset=UTF-8", "application/xml", "application/soap+xml"}
    )
    public ResponseEntity<String> enviarDocumento(@RequestBody(required = false) String xml) {
        try {
            SapEnviarDocumento documento = sapXmlDocumentParser.parse(xml);
            ApiTenant tenant = sapTenantResolver.requireFromDocumento(documento);
            CreateInvoiceRequestDTO dto = ingestInvoiceMapper.fromSap(documento, xml);
            InvoiceOrchestratorService.SapEmissionOutcome outcome = invoiceOrchestratorService
                    .processSapInvoiceAndWait(dto, tenant, dianWaitTimeout);

            if (outcome.accepted()) {
                return xmlOk(SapSoapResponse.success(
                        outcome.mensaje(),
                        outcome.cufe(),
                        outcome.invoiceId().toString(),
                        outcome.documentNumber()
                ));
            }
            return xmlOk(SapSoapResponse.error(SapSoapResponse.CODIGO_DIAN, outcome.mensaje()));
        } catch (IllegalArgumentException ex) {
            log.warn("SAP SOAP rechazado: {}", ex.getMessage());
            String codigo = authError(ex) ? SapSoapResponse.CODIGO_AUTH : SapSoapResponse.CODIGO_NEGOCIO;
            return xmlOk(SapSoapResponse.error(codigo, ex.getMessage()));
        } catch (InvoiceEmissionException ex) {
            log.warn("SAP SOAP error de emisión: {}", ex.getMessage());
            return xmlOk(SapSoapResponse.error(SapSoapResponse.CODIGO_DIAN, ex.getMessage()));
        } catch (Exception ex) {
            log.error("SAP SOAP fault", ex);
            return ResponseEntity
                    .status(500)
                    .contentType(MediaType.TEXT_XML)
                    .body(SapSoapResponse.fault(ex.getMessage()));
        }
    }

    private static boolean authError(IllegalArgumentException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        return message.contains("credencial")
                || message.contains("usuario")
                || message.contains("contrasen")
                || message.contains("idEmpresa".toLowerCase());
    }

    private static ResponseEntity<String> xmlOk(String body) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/xml;charset=UTF-8"))
                .body(body);
    }
}
