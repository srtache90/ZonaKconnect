package com.zonak.portal.controller;

import com.zonak.portal.exception.InvoiceEmissionException;
import com.zonak.portal.exception.InvoiceStorageException;
import com.zonak.portal.integration.IngestInvoiceMapper;
import com.zonak.portal.integration.sap.SapXmlDocumentParser;
import com.zonak.portal.service.InvoiceOrchestratorService;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class XmlImportPortalController {
    private static final String LOCAL_TENANT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String LOCAL_EMISSION_POINT_ID = "00000000-0000-0000-0000-000000000101";

    private final SapXmlDocumentParser sapXmlDocumentParser;
    private final IngestInvoiceMapper ingestInvoiceMapper;
    private final InvoiceOrchestratorService invoiceOrchestratorService;
    private final boolean localMode;

    public XmlImportPortalController(
            SapXmlDocumentParser sapXmlDocumentParser,
            IngestInvoiceMapper ingestInvoiceMapper,
            InvoiceOrchestratorService invoiceOrchestratorService,
            @Value("${aws.local-mode:false}") boolean localMode
    ) {
        this.sapXmlDocumentParser = sapXmlDocumentParser;
        this.ingestInvoiceMapper = ingestInvoiceMapper;
        this.invoiceOrchestratorService = invoiceOrchestratorService;
        this.localMode = localMode;
    }

    @GetMapping("/portal/importar-xml")
    public String importarXml(Model model) {
        model.addAttribute("navModule", "emision");
        model.addAttribute("navActive", "emision");
        return "portal/importar-xml";
    }

    @PostMapping("/portal/importar-xml")
    public String importarXml(
            @RequestParam MultipartFile archivoXml,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        if (archivoXml.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Debe seleccionar un archivo XML.");
            return "redirect:/portal/importar-xml";
        }

        String tenantId = resolveTenantId(session);
        String emissionPointId = resolveEmissionPointId(session);

        try {
            String xml = new String(archivoXml.getBytes(), StandardCharsets.UTF_8);
            UUID invoiceId = invoiceOrchestratorService
                    .processAndPersistInvoice(
                            ingestInvoiceMapper.fromSap(sapXmlDocumentParser.parse(xml)),
                            tenantId,
                            emissionPointId
                    )
                    .block();

            redirectAttributes.addFlashAttribute("success", invoiceId);
        } catch (InvoiceStorageException | InvoiceEmissionException ex) {
            redirectAttributes.addFlashAttribute("error", readableError(ex));
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("error", "XML inválido o no compatible con SAP: " + ex.getMessage());
        }

        return "redirect:/portal/importar-xml";
    }

    private String resolveTenantId(HttpSession session) {
        Object tenantId = session.getAttribute("tenantId");
        if (tenantId != null) {
            return tenantId.toString();
        }

        if (localMode) {
            session.setAttribute("tenantId", LOCAL_TENANT_ID);
            session.setAttribute("emissionPointId", LOCAL_EMISSION_POINT_ID);
            return LOCAL_TENANT_ID;
        }

        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "tenantId no existe en sesión");
    }

    private String resolveEmissionPointId(HttpSession session) {
        Object emissionPointId = session.getAttribute("emissionPointId");
        if (emissionPointId != null) {
            return emissionPointId.toString();
        }

        if (localMode) {
            session.setAttribute("emissionPointId", LOCAL_EMISSION_POINT_ID);
            return LOCAL_EMISSION_POINT_ID;
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "punto de emisión no existe en sesión");
    }

    private String readableError(RuntimeException ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? "No fue posible importar, firmar y enviar el XML"
                : ex.getMessage();
    }
}
