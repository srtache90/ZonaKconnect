package com.zonak.portal.controller;

import com.zonak.portal.admin.Sociedad;
import com.zonak.portal.mail.InvoiceMailDispatchService;
import com.zonak.portal.mail.MailReceptionSyncService;
import com.zonak.portal.recepcion.ReceivedInvoiceRepository;
import com.zonak.portal.recepcion.ReceivedInvoiceRow;
import com.zonak.portal.service.InvoiceOrchestratorService;
import com.zonak.portal.service.PortalSessionService;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class RecepcionPortalController {
    private final PortalSessionService portalSessionService;
    private final ReceivedInvoiceRepository receivedInvoiceRepository;
    private final InvoiceOrchestratorService invoiceOrchestratorService;
    private final MailReceptionSyncService mailReceptionSyncService;
    private final InvoiceMailDispatchService invoiceMailDispatchService;

    public RecepcionPortalController(
            PortalSessionService portalSessionService,
            ReceivedInvoiceRepository receivedInvoiceRepository,
            InvoiceOrchestratorService invoiceOrchestratorService,
            MailReceptionSyncService mailReceptionSyncService,
            InvoiceMailDispatchService invoiceMailDispatchService
    ) {
        this.portalSessionService = portalSessionService;
        this.receivedInvoiceRepository = receivedInvoiceRepository;
        this.invoiceOrchestratorService = invoiceOrchestratorService;
        this.mailReceptionSyncService = mailReceptionSyncService;
        this.invoiceMailDispatchService = invoiceMailDispatchService;
    }

    @GetMapping("/portal/recepcion")
    public String recepcionInicio() {
        return "redirect:/portal/recepcion/bandeja";
    }

    @GetMapping("/portal/recepcion/bandeja")
    public String bandeja(
            @RequestParam(required = false) String sociedadId,
            HttpSession session,
            Model model
    ) {
        List<Sociedad> sociedades = portalSessionService.resolveSociedades(session);
        if (sociedades.isEmpty()) {
            model.addAttribute("sociedades", sociedades);
            model.addAttribute("invoices", List.of());
            model.addAttribute("selectedSociedadId", "");
            model.addAttribute("selectedSociedadNombre", "Sin sociedad");
            model.addAttribute("navModule", "recepcion");
            model.addAttribute("navActive", "bandeja");
            return "portal/recepcion_bandeja";
        }

        String selectedSociedadId = resolveSociedadId(sociedadId, session, sociedades);
        UUID tenantUuid = UUID.fromString(selectedSociedadId);
        Sociedad selectedSociedad = sociedades.stream()
                .filter(s -> s.id().equals(tenantUuid))
                .findFirst()
                .orElse(sociedades.getFirst());

        List<ReceivedInvoiceRow> invoices = receivedInvoiceRepository.findBySociedad(tenantUuid);

        model.addAttribute("sociedades", sociedades);
        model.addAttribute("invoices", invoices);
        model.addAttribute("selectedSociedadId", selectedSociedadId);
        model.addAttribute("selectedSociedadNombre", selectedSociedad.razonSocial());
        model.addAttribute("navModule", "recepcion");
        model.addAttribute("navActive", "bandeja");
        return "portal/recepcion_bandeja";
    }

    @PostMapping("/portal/recepcion/sincronizar-correo")
    public String sincronizarCorreo(
            @RequestParam(required = false) String sociedadId,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        String tenantId = resolveSociedadId(sociedadId, session, portalSessionService.resolveSociedades(session));
        try {
            int imported = mailReceptionSyncService.syncInbox(UUID.fromString(tenantId));
            redirectAttributes.addFlashAttribute(
                    "success",
                    "Sincronización completada. Documentos importados: " + imported + "."
            );
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    "No fue posible sincronizar el correo: " + ex.getMessage()
            );
        }
        return bandejaRedirect(tenantId);
    }

    @GetMapping("/portal/recepcion/{id}/representacion-grafica")
    public ResponseEntity<byte[]> representacionGrafica(
            @PathVariable UUID id,
            HttpSession session
    ) {
        UUID tenantId = UUID.fromString(portalSessionService.resolveTenantId(session));
        byte[] pdfBytes = invoiceOrchestratorService.downloadOrGeneratePdf(tenantId, id).block();
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PDF no disponible para la factura recibida");
        }

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"factura-recibida-" + id + ".pdf\"")
                .body(pdfBytes);
    }

    @GetMapping("/portal/recepcion/{id}/xml")
    public ResponseEntity<byte[]> descargarXml(
            @PathVariable UUID id,
            HttpSession session
    ) {
        UUID tenantId = UUID.fromString(portalSessionService.resolveTenantId(session));
        String xml = receivedInvoiceRepository.findXmlBase(tenantId, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "XML no disponible para la factura recibida"));
        byte[] xmlBytes = xml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"factura-recibida-" + id + ".xml\"")
                .body(xmlBytes);
    }

    @PostMapping("/portal/recepcion/{id}/reenviar-correo")
    public String reenviarCorreo(
            @PathVariable UUID id,
            @RequestParam String recipientEmail,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        String tenantId = portalSessionService.resolveTenantId(session);
        try {
            String message = invoiceMailDispatchService.sendInvoiceDocuments(tenantId, id, recipientEmail);
            redirectAttributes.addFlashAttribute("success", message);
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    "No fue posible reenviar el correo: " + ex.getMessage()
            );
        }
        return bandejaRedirect(tenantId);
    }

    private String bandejaRedirect(String sociedadId) {
        if (sociedadId == null || sociedadId.isBlank()) {
            return "redirect:/portal/recepcion/bandeja";
        }
        return "redirect:/portal/recepcion/bandeja?sociedadId=" + sociedadId;
    }

    private String resolveSociedadId(String requestedSociedadId, HttpSession session, List<Sociedad> sociedades) {
        if (requestedSociedadId != null && !requestedSociedadId.isBlank()) {
            try {
                UUID parsed = UUID.fromString(requestedSociedadId.trim());
                boolean allowed = sociedades.stream().anyMatch(s -> s.id().equals(parsed));
                if (allowed) {
                    session.setAttribute("tenantId", parsed.toString());
                    return parsed.toString();
                }
            } catch (IllegalArgumentException ignored) {
                // se usa la sociedad activa de sesión
            }
        }
        return portalSessionService.resolveSelectedSociedadId(session, sociedades);
    }
}
