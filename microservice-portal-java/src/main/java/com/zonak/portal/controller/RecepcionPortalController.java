package com.zonak.portal.controller;

import com.zonak.portal.admin.Sociedad;
import com.zonak.portal.mail.InvoiceMailDispatchService;
import com.zonak.portal.mail.MailReceptionSyncService;
import com.zonak.portal.recepcion.ReceivedInvoiceDetail;
import com.zonak.portal.recepcion.ReceivedInvoiceFilter;
import com.zonak.portal.recepcion.ReceivedInvoiceRepository;
import com.zonak.portal.recepcion.ReceivedInvoiceRow;
import com.zonak.portal.recepcion.RecepcionEstadoDian;
import com.zonak.portal.recepcion.RecepcionTacitAcceptanceService;
import com.zonak.portal.service.PortalSessionService;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class RecepcionPortalController {
    private final PortalSessionService portalSessionService;
    private final ReceivedInvoiceRepository receivedInvoiceRepository;
    private final MailReceptionSyncService mailReceptionSyncService;
    private final InvoiceMailDispatchService invoiceMailDispatchService;
    private final RecepcionTacitAcceptanceService recepcionTacitAcceptanceService;

    public RecepcionPortalController(
            PortalSessionService portalSessionService,
            ReceivedInvoiceRepository receivedInvoiceRepository,
            MailReceptionSyncService mailReceptionSyncService,
            InvoiceMailDispatchService invoiceMailDispatchService,
            RecepcionTacitAcceptanceService recepcionTacitAcceptanceService
    ) {
        this.portalSessionService = portalSessionService;
        this.receivedInvoiceRepository = receivedInvoiceRepository;
        this.mailReceptionSyncService = mailReceptionSyncService;
        this.invoiceMailDispatchService = invoiceMailDispatchService;
        this.recepcionTacitAcceptanceService = recepcionTacitAcceptanceService;
    }

    @GetMapping("/portal/recepcion")
    public String recepcionInicio() {
        return "redirect:/portal/recepcion/bandeja";
    }

    @GetMapping("/portal/recepcion/bandeja")
    public String bandeja(
            @RequestParam(required = false) String sociedadId,
            @RequestParam(required = false) String estadoDian,
            @RequestParam(required = false) String proveedor,
            @RequestParam(required = false) String cufe,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            HttpSession session,
            Model model
    ) {
        return renderList(
                "portal/recepcion_bandeja",
                "bandeja",
                true,
                sociedadId,
                estadoDian,
                proveedor,
                cufe,
                fromDate,
                toDate,
                null,
                null,
                session,
                model
        );
    }

    @GetMapping("/portal/recepcion/historico")
    public String historico(
            @RequestParam(required = false) String sociedadId,
            @RequestParam(required = false) String eventCode,
            @RequestParam(required = false) String estadoDian,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            HttpSession session,
            Model model
    ) {
        // Histórico = misma vista unificada de eventos RADIAN enviados
        return "redirect:/portal/recepcion/reportes"
                + (sociedadId != null ? "?sociedadId=" + sociedadId : "");
    }

    @GetMapping("/portal/recepcion/{id}/detalle")
    public String detalle(
            @PathVariable UUID id,
            HttpSession session,
            Model model
    ) {
        UUID tenantId = receivedInvoiceRepository
                .findOwnedCompanyId(id, portalSessionService.resolveSociedadIds(session))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Factura recibida no encontrada"));
        ReceivedInvoiceDetail detail = receivedInvoiceRepository.findDetail(tenantId, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Factura recibida no encontrada"));

        List<Sociedad> sociedades = portalSessionService.resolveSociedades(session);
        Sociedad selected = sociedades.stream()
                .filter(s -> s.id().equals(tenantId))
                .findFirst()
                .orElse(null);

        model.addAttribute("detail", detail);
        model.addAttribute("sociedades", sociedades);
        model.addAttribute("selectedSociedadId", tenantId.toString());
        model.addAttribute("selectedSociedadNombre", selected == null ? "Sociedad" : selected.razonSocial());
        model.addAttribute("navModule", "recepcion");
        model.addAttribute("navActive", "bandeja");
        return "portal/recepcion/detalle";
    }

    @PostMapping("/portal/recepcion/aplicar-aceptacion-tacita")
    public String aplicarAceptacionTacita(
            @RequestParam(required = false) String sociedadId,
            @RequestParam(required = false, defaultValue = "bandeja") String returnTo,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        String tenantId = resolveSociedadId(sociedadId, session, portalSessionService.resolveSociedades(session));
        try {
            RecepcionTacitAcceptanceService.TacitResult result =
                    recepcionTacitAcceptanceService.applyDue(UUID.fromString(tenantId));
            redirectAttributes.addFlashAttribute("success", result.summary());
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    "No fue posible aplicar aceptación tácita: " + ex.getMessage()
            );
        }
        if ("historico".equalsIgnoreCase(returnTo)) {
            return "redirect:/portal/recepcion/historico?sociedadId=" + tenantId;
        }
        return bandejaRedirect(tenantId);
    }

    @PostMapping("/portal/recepcion/sincronizar-correo")
    public String sincronizarCorreo(
            @RequestParam(required = false) String sociedadId,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        String tenantId = resolveSociedadId(sociedadId, session, portalSessionService.resolveSociedades(session));
        try {
            MailReceptionSyncService.SyncResult result = mailReceptionSyncService.syncInbox(UUID.fromString(tenantId));
            redirectAttributes.addFlashAttribute("success", result.summary());
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    "No fue posible sincronizar el correo: " + ex.getMessage()
            );
        }
        return bandejaRedirect(tenantId);
    }

    @PostMapping("/portal/recepcion/importar-xml")
    public String importarXml(
            @RequestParam(required = false) String sociedadId,
            @RequestParam("archivo") MultipartFile archivo,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        String tenantId = resolveSociedadId(sociedadId, session, portalSessionService.resolveSociedades(session));
        try {
            if (archivo == null || archivo.isEmpty()) {
                throw new IllegalArgumentException("Debe seleccionar un archivo XML o ZIP.");
            }
            int imported = mailReceptionSyncService.importXmlDocuments(
                    UUID.fromString(tenantId),
                    archivo.getBytes(),
                    archivo.getOriginalFilename(),
                    "XML_UPLOAD"
            );
            redirectAttributes.addFlashAttribute(
                    "success",
                    "XML importado para la sociedad activa. Documentos nuevos: " + imported + "."
            );
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    "No fue posible importar el XML: " + ex.getMessage()
            );
        }
        return bandejaRedirect(tenantId);
    }

    @GetMapping("/portal/recepcion/{id}/representacion-grafica")
    public ResponseEntity<byte[]> representacionGrafica(
            @PathVariable UUID id,
            HttpSession session
    ) {
        UUID tenantId = receivedInvoiceRepository
                .findOwnedCompanyId(id, portalSessionService.resolveSociedadIds(session))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Factura recibida no encontrada"));
        byte[] storedPdf = receivedInvoiceRepository.findPdfBase(tenantId, id).orElse(null);
        if (storedPdf == null || storedPdf.length == 0) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "PDF no disponible. Vuelva a sincronizar el correo o importe el ZIP con la representación gráfica."
            );
        }

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"factura-recibida-" + id + ".pdf\"")
                .body(storedPdf);
    }

    @GetMapping("/portal/recepcion/{id}/xml")
    public ResponseEntity<byte[]> descargarXml(
            @PathVariable UUID id,
            HttpSession session
    ) {
        UUID tenantId = receivedInvoiceRepository
                .findOwnedCompanyId(id, portalSessionService.resolveSociedadIds(session))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Factura recibida no encontrada"));
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
        UUID tenantUuid = receivedInvoiceRepository
                .findOwnedCompanyId(id, portalSessionService.resolveSociedadIds(session))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Factura recibida no encontrada"));
        try {
            var detail = receivedInvoiceRepository.findDetail(tenantUuid, id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Factura recibida no encontrada"));
            byte[] pdf = receivedInvoiceRepository.findPdfBase(tenantUuid, id).orElse(null);
            String xml = receivedInvoiceRepository.findXmlBase(tenantUuid, id).orElse(null);
            byte[] xmlBytes = StringUtils.hasText(xml)
                    ? xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : null;
            String message = invoiceMailDispatchService.sendReceivedDocuments(
                    tenantUuid.toString(),
                    id,
                    detail.invoiceNumber(),
                    recipientEmail,
                    pdf,
                    xmlBytes
            );
            redirectAttributes.addFlashAttribute("success", message);
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute(
                    "error",
                    "No fue posible reenviar el correo: " + ex.getMessage()
            );
        }
        return "redirect:/portal/recepcion/" + id + "/detalle";
    }

    private String renderList(
            String view,
            String navActive,
            boolean openOnly,
            String sociedadId,
            String estadoDian,
            String proveedor,
            String cufe,
            String fromDate,
            String toDate,
            String minTotal,
            String maxTotal,
            HttpSession session,
            Model model
    ) {
        List<Sociedad> sociedades = portalSessionService.resolveSociedades(session);
        if (sociedades.isEmpty()) {
            model.addAttribute("sociedades", sociedades);
            model.addAttribute("invoices", List.of());
            model.addAttribute("selectedSociedadId", "");
            model.addAttribute("selectedSociedadNombre", "Sin sociedad");
            model.addAttribute("estados", RecepcionEstadoDian.values());
            model.addAttribute("alerts", new RecepcionTacitAcceptanceService.AlertsSummary(0, 0, 0));
            model.addAttribute("navModule", "recepcion");
            model.addAttribute("navActive", navActive);
            return view;
        }

        String selectedSociedadId = resolveSociedadId(sociedadId, session, sociedades);
        UUID tenantUuid = UUID.fromString(selectedSociedadId);
        Sociedad selectedSociedad = sociedades.stream()
                .filter(s -> s.id().equals(tenantUuid))
                .findFirst()
                .orElse(sociedades.getFirst());

        ReceivedInvoiceFilter filter = new ReceivedInvoiceFilter(
                tenantUuid,
                parseDate(fromDate),
                parseDate(toDate),
                blankToNull(estadoDian),
                blankToNull(proveedor),
                blankToNull(cufe),
                parseDecimal(minTotal),
                parseDecimal(maxTotal),
                openOnly ? Boolean.TRUE : null
        );
        List<ReceivedInvoiceRow> invoices = receivedInvoiceRepository.findReceived(filter);
        RecepcionTacitAcceptanceService.AlertsSummary alerts =
                recepcionTacitAcceptanceService.summarizeAlerts(invoices);

        model.addAttribute("sociedades", sociedades);
        model.addAttribute("invoices", invoices);
        model.addAttribute("selectedSociedadId", selectedSociedadId);
        model.addAttribute("selectedSociedadNombre", selectedSociedad.razonSocial());
        model.addAttribute("estadoDian", estadoDian);
        model.addAttribute("proveedor", proveedor);
        model.addAttribute("cufe", cufe);
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("minTotal", minTotal);
        model.addAttribute("maxTotal", maxTotal);
        model.addAttribute("estados", RecepcionEstadoDian.values());
        model.addAttribute("alerts", alerts);
        model.addAttribute("navModule", "recepcion");
        model.addAttribute("navActive", navActive);
        return view;
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

    private static LocalDate parseDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private static BigDecimal parseDecimal(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return new BigDecimal(value.trim().replace(",", "."));
        } catch (Exception ex) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
