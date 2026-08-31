package com.zonak.portal.controller;

import com.zonak.portal.admin.AdminPortalRepository;
import com.zonak.portal.admin.PuntoVenta;
import com.zonak.portal.admin.Sociedad;
import com.zonak.portal.dto.CreateCreditNoteRequestDTO;
import com.zonak.portal.dto.CreateDebitNoteRequestDTO;
import com.zonak.portal.dto.CreateInvoiceRequestDTO;
import com.zonak.portal.dto.InvoiceListResponseDTO;
import com.zonak.portal.dto.InvoiceResponseDTO;
import com.zonak.portal.exception.InvoiceEmissionException;
import com.zonak.portal.exception.InvoiceStorageException;
import com.zonak.portal.mail.InvoiceMailDispatchService;
import com.zonak.portal.dto.DianFiscalContext;
import com.zonak.portal.dto.InvoicePdfData;
import com.zonak.portal.service.InvoiceClientService;
import com.zonak.portal.service.InvoiceOrchestratorService;
import com.zonak.portal.service.InvoiceReportRepository;
import com.zonak.portal.service.PortalSessionService;
import com.zonak.portal.support.InvoiceDianStatus;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class InvoicePortalController {
    private static final String LOCAL_TENANT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String LOCAL_EMISSION_POINT_ID = "00000000-0000-0000-0000-000000000101";

    private final InvoiceClientService invoiceClientService;
    private final InvoiceOrchestratorService invoiceOrchestratorService;
    private final InvoiceMailDispatchService invoiceMailDispatchService;
    private final AdminPortalRepository adminPortalRepository;
    private final InvoiceReportRepository invoiceReportRepository;
    private final PortalSessionService portalSessionService;
    private final boolean localMode;

    public InvoicePortalController(
            InvoiceClientService invoiceClientService,
            InvoiceOrchestratorService invoiceOrchestratorService,
            InvoiceMailDispatchService invoiceMailDispatchService,
            AdminPortalRepository adminPortalRepository,
            InvoiceReportRepository invoiceReportRepository,
            PortalSessionService portalSessionService,
            @Value("${aws.local-mode:false}") boolean localMode
    ) {
        this.invoiceClientService = invoiceClientService;
        this.invoiceOrchestratorService = invoiceOrchestratorService;
        this.invoiceMailDispatchService = invoiceMailDispatchService;
        this.adminPortalRepository = adminPortalRepository;
        this.invoiceReportRepository = invoiceReportRepository;
        this.portalSessionService = portalSessionService;
        this.localMode = localMode;
    }

    @GetMapping("/portal/emision")
    public String emision() {
        return "redirect:/portal/invoices";
    }

    @GetMapping("/portal/facturacion/nota-credito")
    public String notaCredito(
            @RequestParam(required = false) String refInvoiceId,
            HttpSession session,
            Model model
    ) {
        populateManualFormModel(session, model, "91", "nota-credito");
        applyCreditNotePrefill(session, refInvoiceId, model);
        model.addAttribute("pageTitle", "Nota crédito electrónica");
        model.addAttribute("pageDescription", "Anule total o parcialmente una factura validada por la DIAN.");
        model.addAttribute("submitLabel", "Emitir nota crédito");
        return "portal/factura_manual";
    }

    @GetMapping("/portal/facturacion/manual")
    public String facturacionManual(
            @RequestParam(required = false) String tipo,
            HttpSession session,
            Model model
    ) {
        String tipoOperacion = normalizeManualTipoOperacion(tipo);
        String navActive = "91".equals(tipoOperacion) ? "nota-credito" : "manual";
        populateManualFormModel(session, model, tipoOperacion, navActive);
        if ("91".equals(tipoOperacion)) {
            model.addAttribute("pageTitle", "Nota crédito electrónica");
            model.addAttribute("pageDescription", "Anule total o parcialmente una factura validada por la DIAN.");
            model.addAttribute("submitLabel", "Emitir nota crédito");
        }
        return "portal/factura_manual";
    }

    private void populateManualFormModel(HttpSession session, Model model, String tipoOperacion, String navActive) {
        List<Sociedad> sociedades = portalSessionService.resolveSociedades(session);
        List<PuntoVenta> puntosVenta = portalSessionService.resolvePuntosVenta(session);
        String selectedSociedadId = portalSessionService.resolveSelectedSociedadId(session, sociedades);
        String selectedEmissionPointId = portalSessionService.resolveSelectedEmissionPointId(
                session,
                puntosVenta,
                selectedSociedadId
        );

        model.addAttribute("sociedades", sociedades);
        model.addAttribute("puntosVenta", puntosVenta);
        model.addAttribute("selectedSociedadId", selectedSociedadId);
        model.addAttribute("selectedEmissionPointId", selectedEmissionPointId);
        model.addAttribute("selectedTipoOperacion", tipoOperacion);
        model.addAttribute("navModule", "emision");
        model.addAttribute("navActive", navActive);
    }

    private void applyCreditNotePrefill(HttpSession session, String refInvoiceId, Model model) {
        if (!StringUtils.hasText(refInvoiceId)) {
            return;
        }

        UUID tenantId;
        UUID invoiceId;
        try {
            tenantId = UUID.fromString(portalSessionService.resolveSelectedSociedadId(
                    session,
                    portalSessionService.resolveSociedades(session)
            ));
            invoiceId = UUID.fromString(refInvoiceId.trim());
        } catch (RuntimeException ex) {
            model.addAttribute("prefillWarning", "No fue posible cargar la factura de referencia.");
            return;
        }

        if (!resolveSociedadIds(session).contains(tenantId)) {
            model.addAttribute("prefillWarning", "La sociedad activa no está autorizada para esta operación.");
            return;
        }

        Optional<InvoicePdfData> invoice = invoiceReportRepository.findInvoice(tenantId, invoiceId);
        if (invoice.isEmpty()) {
            model.addAttribute("prefillWarning", "Factura de referencia no encontrada.");
            return;
        }

        InvoicePdfData data = invoice.get();
        if (data.fiscalContext().documentKind() != DianFiscalContext.DocumentKind.INVOICE) {
            model.addAttribute("prefillWarning", "Solo se puede referenciar una factura de venta (01/05).");
            return;
        }
        if (!InvoiceDianStatus.isValidated(data.status(), data.fiscalContext().uniqueCode())) {
            model.addAttribute("prefillWarning", "La factura referenciada debe estar validada por la DIAN.");
            return;
        }

        invoiceReportRepository.findEmissionPointId(tenantId, invoiceId).ifPresent(emissionPointId ->
                model.addAttribute("selectedEmissionPointId", emissionPointId.toString())
        );

        model.addAttribute("prefillCufeReferencia", data.fiscalContext().uniqueCode());
        model.addAttribute("prefillNumeroDocumentoReferencia", data.documentNumber());
        if (data.fiscalContext().issueDate() != null) {
            model.addAttribute(
                    "prefillFechaEmisionReferencia",
                    data.fiscalContext().issueDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
            );
        }
        if (data.customer() != null) {
            model.addAttribute("prefillIdentificacion", data.customer().identificacion());
            model.addAttribute("prefillRazonSocial", data.customer().razonSocial());
            model.addAttribute("prefillEmail", data.customer().email());
        }
    }

    private static String normalizeManualTipoOperacion(String tipo) {
        if (!StringUtils.hasText(tipo)) {
            return "01";
        }
        return switch (tipo.trim()) {
            case "91", "92", "05" -> tipo.trim();
            default -> "01";
        };
    }

    @PostMapping("/portal/facturacion/manual/emitir")
    public String emitirFacturaManual(
            @ModelAttribute ManualInvoiceForm form,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        List<UUID> sociedadIds = resolveSociedadIds(session);
        UUID tenantUuid;
        UUID emissionPointId;

        try {
            tenantUuid = UUID.fromString(form.getSociedadId());
            emissionPointId = UUID.fromString(form.getEmissionPointId());
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", "Debe seleccionar una sociedad y un punto de venta válidos.");
            return "redirect:/portal/facturacion/manual";
        }

        if (!sociedadIds.contains(tenantUuid)) {
            redirectAttributes.addFlashAttribute("error", "La sociedad seleccionada no está autorizada para este usuario.");
            return "redirect:/portal/facturacion/manual";
        }

        if (!adminPortalRepository.puntoVentaActivoPerteneceASociedad(emissionPointId, tenantUuid)) {
            redirectAttributes.addFlashAttribute("error", "El punto de venta seleccionado no pertenece a la sociedad activa.");
            return "redirect:/portal/facturacion/manual";
        }

        session.setAttribute("tenantId", tenantUuid.toString());
        session.setAttribute("emissionPointId", emissionPointId.toString());

        try {
            String tipoOperacion = form.getTipoOperacion() == null ? "" : form.getTipoOperacion().trim();
            UUID invoiceId;
            if ("91".equals(tipoOperacion)) {
                invoiceId = invoiceOrchestratorService
                        .processAndPersistCreditNote(
                                form.toCreditNoteDTO(),
                                tenantUuid.toString(),
                                emissionPointId.toString()
                        )
                        .block();
            } else if ("92".equals(tipoOperacion)) {
                invoiceId = invoiceOrchestratorService
                        .processAndPersistDebitNote(
                                form.toDebitNoteDTO(),
                                tenantUuid.toString(),
                                emissionPointId.toString()
                        )
                        .block();
            } else {
                invoiceId = invoiceOrchestratorService
                        .processAndPersistInvoice(form.toDTO(), tenantUuid.toString(), emissionPointId.toString())
                        .block();
            }
            redirectAttributes.addFlashAttribute("success", "Documento emitido correctamente. ID: " + invoiceId);
        } catch (InvoiceStorageException | InvoiceEmissionException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", readableError(ex));
            return "redirect:" + manualFormReturnPath(form.getTipoOperacion());
        }

        return "redirect:/portal/invoices";
    }

    private static String manualFormReturnPath(String tipoOperacion) {
        if ("91".equals(tipoOperacion == null ? "" : tipoOperacion.trim())) {
            return "/portal/facturacion/nota-credito";
        }
        return "/portal/facturacion/manual";
    }

    @GetMapping("/portal/invoices")
    public String getInvoices(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String documentKind,
            @RequestParam(required = false) String sociedadId,
            @RequestParam(required = false) String emissionPointId,
            HttpSession session,
            Model model
    ) {
        if ("RECIBIDA".equalsIgnoreCase(tipo)) {
            return "redirect:/portal/recepcion/bandeja";
        }
        List<Sociedad> sociedades = resolveSociedades(session);
        List<UUID> sociedadIds = sociedades.stream()
                .map(Sociedad::id)
                .toList();
        if (sociedadId != null && !sociedadId.isBlank()) {
            try {
                UUID parsed = UUID.fromString(sociedadId.trim());
                if (sociedadIds.contains(parsed)) {
                    session.setAttribute("tenantId", parsed.toString());
                }
            } catch (IllegalArgumentException ignored) {
                // se conserva la sociedad de sesión
            }
        }
        String tenantId = resolveTenantId(session);
        String selectedSociedadId = resolveSelectedSociedadId(session, sociedades);
        List<PuntoVenta> puntosVenta = adminPortalRepository.findPuntosVentaActivosBySociedades(sociedadIds);
        if (emissionPointId != null && !emissionPointId.isBlank()) {
            session.setAttribute("emissionPointId", emissionPointId);
        }
        String selectedEmissionPointId = resolveSelectedEmissionPointId(session, puntosVenta, selectedSociedadId);
        if (selectedEmissionPointId.isBlank()) {
            selectedEmissionPointId = LOCAL_EMISSION_POINT_ID;
            session.setAttribute("emissionPointId", selectedEmissionPointId);
        }

        int safePage = Math.max(page, 1);
        int safeLimit = Math.min(Math.max(limit, 1), 100);

        InvoiceListResponseDTO response = invoiceClientService
                .getInvoices(safePage, safeLimit, estado, tipo, documentKind, tenantId, selectedEmissionPointId)
                .block();

        List<InvoiceListResponseDTO.InvoiceItemDTO> invoices = response != null && response.invoices() != null
                ? response.invoices()
                : List.of();
        long totalRecords = response != null ? response.totalRecords() : 0;
        int totalPages = safeLimit > 0 ? (int) Math.ceil((double) totalRecords / safeLimit) : 0;

        model.addAttribute("tenantId", tenantId);
        model.addAttribute("sociedades", sociedades);
        model.addAttribute("selectedSociedadId", selectedSociedadId);
        model.addAttribute("puntosVenta", puntosVenta);
        model.addAttribute("selectedEmissionPointId", selectedEmissionPointId);
        model.addAttribute("invoices", invoices);
        model.addAttribute("page", safePage);
        model.addAttribute("limit", safeLimit);
        model.addAttribute("totalRecords", totalRecords);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("hasPrevious", safePage > 1);
        model.addAttribute("hasNext", safePage < totalPages);
        model.addAttribute("previousPage", Math.max(safePage - 1, 1));
        model.addAttribute("nextPage", safePage + 1);
        model.addAttribute("estado", estado);
        model.addAttribute("tipo", tipo);
        model.addAttribute("documentKind", documentKind);
        model.addAttribute("navModule", "emision");
        model.addAttribute("navActive", "emision");

        return "portal/invoices";
    }

    @PostMapping("/portal/invoices/emit")
    public String emitInvoice(
            @ModelAttribute CreateInvoiceForm form,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        List<UUID> sociedadIds = resolveSociedadIds(session);
        UUID tenantUuid;
        UUID emissionPointId;

        try {
            tenantUuid = UUID.fromString(form.getSociedadId());
            emissionPointId = UUID.fromString(form.getEmissionPointId());
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", "Debe seleccionar una sociedad y un punto de venta válidos.");
            return "redirect:/portal/invoices";
        }

        if (!sociedadIds.contains(tenantUuid)) {
            redirectAttributes.addFlashAttribute("error", "La sociedad seleccionada no está autorizada para este usuario.");
            return "redirect:/portal/invoices";
        }

        if (!adminPortalRepository.puntoVentaActivoPerteneceASociedad(emissionPointId, tenantUuid)) {
            redirectAttributes.addFlashAttribute("error", "El punto de venta seleccionado no pertenece a la sociedad activa.");
            return "redirect:/portal/invoices";
        }

        String tenantId = tenantUuid.toString();
        session.setAttribute("tenantId", tenantId);
        session.setAttribute("emissionPointId", emissionPointId.toString());

        try {
            UUID invoiceId = invoiceOrchestratorService
                    .processAndPersistInvoice(form.toDTO(), tenantId, emissionPointId.toString())
                    .block();

            redirectAttributes.addFlashAttribute("success", invoiceId);
        } catch (InvoiceStorageException | InvoiceEmissionException ex) {
            redirectAttributes.addFlashAttribute("error", readableError(ex));
        }

        return "redirect:/portal/invoices";
    }

    @GetMapping("/portal/invoices/{id}/documents/{kind}")
    public ResponseEntity<byte[]> downloadInvoiceDocument(
            @PathVariable UUID id,
            @PathVariable String kind,
            HttpSession session
    ) {
        String tenantId = resolveTenantId(session);
        String emissionPointId = resolveEmissionPointIdForDownload(session);
        if (kind.equalsIgnoreCase("pdf") || kind.equalsIgnoreCase("representacion-grafica")) {
            try {
                byte[] pdfBytes = invoiceOrchestratorService
                        .downloadOrGeneratePdf(UUID.fromString(tenantId), id)
                        .block();
                if (pdfBytes == null || pdfBytes.length == 0) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PDF no disponible");
                }

                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"factura-%s.pdf\"".formatted(id))
                        .body(pdfBytes);
            } catch (InvoiceStorageException ex) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, readableError(ex), ex);
            } catch (RuntimeException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof InvoiceStorageException storageEx) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, readableError(storageEx), storageEx);
                }
                throw ex;
            }
        }

        ResponseEntity<byte[]> coreResponse;
        try {
            coreResponse = invoiceClientService
                    .downloadInvoiceDocument(id, kind, tenantId, emissionPointId)
                    .block();
        } catch (InvoiceEmissionException ex) {
            HttpStatus status = ex.statusCode().is4xxClientError()
                    ? HttpStatus.NOT_FOUND
                    : HttpStatus.BAD_GATEWAY;
            throw new ResponseStatusException(status, readableError(ex), ex);
        }
        if (coreResponse == null || coreResponse.getBody() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Documento no disponible");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.putAll(coreResponse.getHeaders());
        return new ResponseEntity<>(coreResponse.getBody(), headers, coreResponse.getStatusCode());
    }

    @PostMapping("/portal/invoices/{id}/reemit")
    public String reemitInvoice(
            @PathVariable UUID id,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        String tenantId = resolveTenantId(session);
        String emissionPointId = resolveEmissionPointIdForDownload(session);
        try {
            InvoiceResponseDTO response = invoiceClientService
                    .reemitInvoice(id, tenantId, emissionPointId)
                    .block();
            String status = response != null && StringUtils.hasText(response.status())
                    ? response.status()
                    : "EN_REINTENTO";
            redirectAttributes.addFlashAttribute(
                    "success",
                    "Reemisión enviada a DIAN. ID: " + id + " (" + status + ")"
            );
        } catch (InvoiceEmissionException ex) {
            redirectAttributes.addFlashAttribute("error", readableError(ex));
        }
        return "redirect:/portal/invoices";
    }

    @PostMapping("/portal/invoices/{id}/documents/attachment/send")
    public String sendInvoiceAttachment(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "ORIGINAL") String recipientMode,
            @RequestParam(required = false) String recipientEmail,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        String tenantId = resolveTenantId(session);
        String emissionPointId = resolveEmissionPointIdForDownload(session);
        try {
            String email = resolveAttachmentRecipient(id, tenantId, emissionPointId, recipientMode, recipientEmail, session);
            String message = invoiceMailDispatchService.sendInvoiceDocuments(tenantId, id, email, emissionPointId);
            redirectAttributes.addFlashAttribute("success", message);
        } catch (IllegalArgumentException | IllegalStateException | InvoiceEmissionException | InvoiceStorageException ex) {
            redirectAttributes.addFlashAttribute("error", readableError(ex));
        }
        return "redirect:/portal/invoices";
    }

    private String resolveAttachmentRecipient(
            UUID invoiceId,
            String tenantId,
            String emissionPointId,
            String recipientMode,
            String recipientEmail,
            HttpSession session
    ) {
        if ("CUSTOM".equalsIgnoreCase(recipientMode)) {
            if (!StringUtils.hasText(recipientEmail)) {
                throw new IllegalArgumentException("Debe indicar un correo alterno");
            }
            return recipientEmail.trim();
        }

        if (StringUtils.hasText(recipientEmail)) {
            return recipientEmail.trim();
        }

        Object sessionEmail = session.getAttribute("lastCustomerEmail");
        if (sessionEmail != null && StringUtils.hasText(sessionEmail.toString())) {
            return sessionEmail.toString().trim();
        }

        InvoiceListResponseDTO response = invoiceClientService
                .getInvoices(1, 100, null, null, tenantId, emissionPointId)
                .block();
        if (response != null && response.invoices() != null) {
            for (InvoiceListResponseDTO.InvoiceItemDTO invoice : response.invoices()) {
                if (invoice != null && invoiceId.equals(invoice.id()) && StringUtils.hasText(invoice.customerEmail())) {
                    return invoice.customerEmail().trim();
                }
            }
        }

        throw new IllegalArgumentException("No hay correo del adquirente disponible para esta factura");
    }

    private String readableError(RuntimeException ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? "No fue posible emitir y almacenar la factura"
                : ex.getMessage();
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

    private String resolveEmissionPointIdForDownload(HttpSession session) {
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

    private List<UUID> resolveSociedadIds(HttpSession session) {
        if (com.zonak.portal.security.PortalRoles.isAdmin(
                session.getAttribute("role") == null ? null : session.getAttribute("role").toString())) {
            return adminPortalRepository.findSociedades().stream()
                    .map(Sociedad::id)
                    .toList();
        }

        Object tenantIds = session.getAttribute("tenantIds");
        if (tenantIds instanceof List<?> list && !list.isEmpty()) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .map(UUID::fromString)
                    .toList();
        }

        String tenantId = resolveTenantId(session);
        return List.of(UUID.fromString(tenantId));
    }

    private List<Sociedad> resolveSociedades(HttpSession session) {
        if (com.zonak.portal.security.PortalRoles.isAdmin(
                session.getAttribute("role") == null ? null : session.getAttribute("role").toString())) {
            return adminPortalRepository.findSociedades();
        }

        return adminPortalRepository.findSociedadesByIds(resolveSociedadIds(session));
    }

    private String resolveSelectedSociedadId(
            HttpSession session,
            List<Sociedad> sociedades
    ) {
        Object sessionTenantId = session.getAttribute("tenantId");
        if (sessionTenantId != null) {
            String value = sessionTenantId.toString();
            boolean exists = sociedades.stream()
                    .anyMatch(sociedad -> sociedad.id().toString().equals(value));
            if (exists) {
                return value;
            }
        }

        if (sociedades.isEmpty()) {
            return "";
        }

        String firstSociedadId = sociedades.getFirst().id().toString();
        session.setAttribute("tenantId", firstSociedadId);
        return firstSociedadId;
    }

    private String resolveSelectedEmissionPointId(
            HttpSession session,
            List<PuntoVenta> puntosVenta,
            String selectedSociedadId
    ) {
        Object sessionEmissionPointId = session.getAttribute("emissionPointId");
        if (sessionEmissionPointId != null) {
            String value = sessionEmissionPointId.toString();
            boolean exists = puntosVenta.stream()
                    .anyMatch(puntoVenta -> puntoVenta.id().toString().equals(value)
                            && puntoVenta.sociedadId().toString().equals(selectedSociedadId));
            if (exists) {
                return value;
            }
        }

        return puntosVenta.stream()
                .filter(puntoVenta -> puntoVenta.sociedadId().toString().equals(selectedSociedadId))
                .findFirst()
                .map(puntoVenta -> {
                    String value = puntoVenta.id().toString();
                    session.setAttribute("emissionPointId", value);
                    return value;
                })
                .orElse("");
    }

    private static String mapIdentificationTypeForDian(String code) {
        if (code == null || code.isBlank()) {
            return "31";
        }
        return switch (code.trim().toUpperCase()) {
            case "CC", "13" -> "13";
            case "CE", "22" -> "22";
            case "PA", "42" -> "42";
            case "NIT", "31" -> "31";
            default -> code.trim();
        };
    }

    private static String normalizeIdentificacion(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[^0-9]", "");
    }

    private static String normalizeRazonSocial(String value) {
        if (value == null || value.isBlank()) {
            return "Consumidor final";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private static List<CreateInvoiceRequestDTO.TaxDTO> buildLineTaxes(BigDecimal base, BigDecimal taxRate) {
        if (base == null || base.compareTo(BigDecimal.ZERO) <= 0
                || taxRate == null || taxRate.compareTo(BigDecimal.ZERO) <= 0) {
            return List.of();
        }
        BigDecimal taxAmount = base.multiply(taxRate)
                .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
        return List.of(new CreateInvoiceRequestDTO.TaxDTO(
                "01",
                "IVA",
                taxRate,
                base,
                taxAmount
        ));
    }

    public static class CreateInvoiceForm {
        private String sociedadId;
        private String emissionPointId;
        private String clienteRazonSocial;
        private String clienteEmail;
        private String clienteIdentificacion;
        private String clienteTipoIdentificacion;
        private String itemCodigo;
        private String itemDescripcion;
        private BigDecimal itemCantidad;
        private BigDecimal itemPrecioUnitario;

        public CreateInvoiceRequestDTO toDTO() {
            BigDecimal safeCantidad = itemCantidad != null ? itemCantidad : BigDecimal.ONE;
            BigDecimal safePrecio = itemPrecioUnitario != null ? itemPrecioUnitario : BigDecimal.ZERO;
            BigDecimal total = safeCantidad.multiply(safePrecio);

            return new CreateInvoiceRequestDTO(
                    "",
                    new CreateInvoiceRequestDTO.CustomerDTO(
                            mapIdentificationTypeForDian(defaultIfBlank(clienteTipoIdentificacion, "31")),
                            normalizeIdentificacion(clienteIdentificacion),
                            normalizeRazonSocial(clienteRazonSocial),
                            clienteEmail
                    ),
                    List.of(new CreateInvoiceRequestDTO.ItemDTO(
                            defaultIfBlank(itemCodigo, "SW-001"),
                            defaultIfBlank(itemDescripcion, "Servicio de Software"),
                            safeCantidad,
                            safePrecio,
                            BigDecimal.ZERO,
                            null
                    )),
                    "",
                    Map.of("subtotal", total, "total", total)
            );
        }

        public String getSociedadId() {
            return sociedadId;
        }

        public void setSociedadId(String sociedadId) {
            this.sociedadId = sociedadId;
        }

        public String getEmissionPointId() {
            return emissionPointId;
        }

        public void setEmissionPointId(String emissionPointId) {
            this.emissionPointId = emissionPointId;
        }

        public String getClienteRazonSocial() {
            return clienteRazonSocial;
        }

        public void setClienteRazonSocial(String clienteRazonSocial) {
            this.clienteRazonSocial = clienteRazonSocial;
        }

        public String getClienteEmail() {
            return clienteEmail;
        }

        public void setClienteEmail(String clienteEmail) {
            this.clienteEmail = clienteEmail;
        }

        public String getClienteIdentificacion() {
            return clienteIdentificacion;
        }

        public void setClienteIdentificacion(String clienteIdentificacion) {
            this.clienteIdentificacion = clienteIdentificacion;
        }

        public String getClienteTipoIdentificacion() {
            return clienteTipoIdentificacion;
        }

        public void setClienteTipoIdentificacion(String clienteTipoIdentificacion) {
            this.clienteTipoIdentificacion = clienteTipoIdentificacion;
        }

        public String getItemCodigo() {
            return itemCodigo;
        }

        public void setItemCodigo(String itemCodigo) {
            this.itemCodigo = itemCodigo;
        }

        public String getItemDescripcion() {
            return itemDescripcion;
        }

        public void setItemDescripcion(String itemDescripcion) {
            this.itemDescripcion = itemDescripcion;
        }

        public BigDecimal getItemCantidad() {
            return itemCantidad;
        }

        public void setItemCantidad(BigDecimal itemCantidad) {
            this.itemCantidad = itemCantidad;
        }

        public BigDecimal getItemPrecioUnitario() {
            return itemPrecioUnitario;
        }

        public void setItemPrecioUnitario(BigDecimal itemPrecioUnitario) {
            this.itemPrecioUnitario = itemPrecioUnitario;
        }

        private String defaultIfBlank(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    public static class ManualInvoiceForm {
        private String sociedadId;
        private String emissionPointId;
        private String tipoOperacion;
        private String tipoDocumentoIdentidad;
        private String identificacion;
        private String razonSocial;
        private String direccion;
        private String email;
        private BigDecimal propinaValor = BigDecimal.ZERO;
        private List<ManualItemForm> items = new ArrayList<>();
        private String cufeReferencia;
        private String numeroDocumentoReferencia;
        private String fechaEmisionReferencia;
        private String conceptoCredito;

        public CreateInvoiceRequestDTO toDTO() {
            List<CreateInvoiceRequestDTO.ItemDTO> itemDTOs = buildItemDtos();
            TotalsSnapshot totalsSnapshot = computeTotals(itemDTOs);
            Map<String, Object> totals = new HashMap<>();
            totals.put("subtotal", totalsSnapshot.subtotal());
            totals.put("iva", totalsSnapshot.iva());
            totals.put("propina", totalsSnapshot.propina());
            totals.put("total", totalsSnapshot.total());

            String tipo = tipoOperacion == null ? "" : tipoOperacion.trim();
            String xmlBase = "";
            if ("05".equals(tipo)) {
                totals.put("contingency", true);
                totals.put("tipoOperacion", "05");
                xmlBase = "tipoOperacion=05";
            }

            return new CreateInvoiceRequestDTO(
                    "",
                    new CreateInvoiceRequestDTO.CustomerDTO(
                            mapIdentificationTypeForDian(tipoDocumentoIdentidad),
                            normalizeIdentificacion(identificacion),
                            normalizeRazonSocial(razonSocial),
                            email
                    ),
                    itemDTOs,
                    xmlBase,
                    totals
            );
        }

        public CreateCreditNoteRequestDTO toCreditNoteDTO() {
            if (!StringUtils.hasText(cufeReferencia)) {
                throw new IllegalArgumentException("CUFE/CUDE de referencia es obligatorio para nota crédito");
            }
            if (!StringUtils.hasText(numeroDocumentoReferencia)) {
                throw new IllegalArgumentException("Número de factura referenciada es obligatorio para nota crédito");
            }

            List<CreateInvoiceRequestDTO.ItemDTO> itemDTOs = buildItemDtos();
            TotalsSnapshot totalsSnapshot = computeTotals(itemDTOs);
            Map<String, Object> totals = new HashMap<>();
            totals.put("subtotal", totalsSnapshot.subtotal());
            totals.put("iva", totalsSnapshot.iva());
            totals.put("propina", totalsSnapshot.propina());
            totals.put("total", totalsSnapshot.total());

            String conceptoCodigo = defaultIfBlank(conceptoCredito, "1");
            String numeroRef = numeroDocumentoReferencia.trim();
            String fechaEmision = formatFechaEmisionReferencia(fechaEmisionReferencia);

            return new CreateCreditNoteRequestDTO(
                    "",
                    "20",
                    "91",
                    new CreateInvoiceRequestDTO.CustomerDTO(
                            mapIdentificationTypeForDian(tipoDocumentoIdentidad),
                            normalizeIdentificacion(identificacion),
                            normalizeRazonSocial(razonSocial),
                            email
                    ),
                    new CreateCreditNoteRequestDTO.FacturaReferenciaDTO(
                            "FV",
                            numeroRef,
                            fechaEmision,
                            cufeReferencia.trim(),
                            "CUFE-SHA384"
                    ),
                    List.of(new CreateCreditNoteRequestDTO.ConceptoCorreccionDTO(
                            numeroRef,
                            conceptoCodigo,
                            describeConceptoCredito(conceptoCodigo)
                    )),
                    itemDTOs,
                    totals
            );
        }

        public CreateDebitNoteRequestDTO toDebitNoteDTO() {
            if (!StringUtils.hasText(cufeReferencia)) {
                throw new IllegalArgumentException("CUFE/CUDE de referencia es obligatorio para nota débito");
            }
            if (!StringUtils.hasText(numeroDocumentoReferencia)) {
                throw new IllegalArgumentException("Número de factura referenciada es obligatorio para nota débito");
            }

            List<CreateInvoiceRequestDTO.ItemDTO> itemDTOs = buildItemDtos();
            TotalsSnapshot totalsSnapshot = computeTotals(itemDTOs);
            Map<String, Object> totals = new HashMap<>();
            totals.put("subtotal", totalsSnapshot.subtotal());
            totals.put("iva", totalsSnapshot.iva());
            totals.put("propina", totalsSnapshot.propina());
            totals.put("total", totalsSnapshot.total());

            String conceptoCodigo = defaultIfBlank(conceptoCredito, "1");
            String numeroRef = numeroDocumentoReferencia.trim();
            String fechaEmision = formatFechaEmisionReferencia(fechaEmisionReferencia);

            return new CreateDebitNoteRequestDTO(
                    "",
                    "30",
                    "92",
                    new CreateInvoiceRequestDTO.CustomerDTO(
                            mapIdentificationTypeForDian(tipoDocumentoIdentidad),
                            normalizeIdentificacion(identificacion),
                            normalizeRazonSocial(razonSocial),
                            email
                    ),
                    new CreateCreditNoteRequestDTO.FacturaReferenciaDTO(
                            "FV",
                            numeroRef,
                            fechaEmision,
                            cufeReferencia.trim(),
                            "CUFE-SHA384"
                    ),
                    List.of(new CreateCreditNoteRequestDTO.ConceptoCorreccionDTO(
                            numeroRef,
                            conceptoCodigo,
                            describeConceptoDebito(conceptoCodigo)
                    )),
                    itemDTOs,
                    totals
            );
        }

        private List<CreateInvoiceRequestDTO.ItemDTO> buildItemDtos() {
            List<CreateInvoiceRequestDTO.ItemDTO> itemDTOs = new ArrayList<>();
            if (items != null) {
                for (ManualItemForm item : items) {
                    if (item == null || item.getDescripcion() == null || item.getDescripcion().isBlank()) {
                        continue;
                    }
                    BigDecimal cantidad = item.getCantidad() != null ? item.getCantidad() : BigDecimal.ONE;
                    BigDecimal precio = item.getPrecioUnitario() != null ? item.getPrecioUnitario() : BigDecimal.ZERO;
                    BigDecimal base = cantidad.multiply(precio);
                    BigDecimal taxRate = item.getPorcentajeIva() != null ? item.getPorcentajeIva() : new BigDecimal("19");
                    List<CreateInvoiceRequestDTO.TaxDTO> impuestos = InvoicePortalController.buildLineTaxes(base, taxRate);
                    itemDTOs.add(new CreateInvoiceRequestDTO.ItemDTO(
                            defaultIfBlank(item.getCodigo(), "MAN-001"),
                            item.getDescripcion(),
                            cantidad,
                            precio,
                            BigDecimal.ZERO,
                            impuestos
                    ));
                }
            }
            if (itemDTOs.isEmpty()) {
                itemDTOs.add(new CreateInvoiceRequestDTO.ItemDTO(
                        "MAN-001",
                        "Producto o servicio manual",
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        List.of()
                ));
            }
            return itemDTOs;
        }

        private TotalsSnapshot computeTotals(List<CreateInvoiceRequestDTO.ItemDTO> itemDTOs) {
            BigDecimal subtotal = BigDecimal.ZERO;
            BigDecimal iva = BigDecimal.ZERO;
            if (items != null) {
                for (ManualItemForm item : items) {
                    if (item == null || item.getDescripcion() == null || item.getDescripcion().isBlank()) {
                        continue;
                    }
                    BigDecimal cantidad = item.getCantidad() != null ? item.getCantidad() : BigDecimal.ONE;
                    BigDecimal precio = item.getPrecioUnitario() != null ? item.getPrecioUnitario() : BigDecimal.ZERO;
                    BigDecimal base = cantidad.multiply(precio);
                    BigDecimal taxRate = item.getPorcentajeIva() != null ? item.getPorcentajeIva() : BigDecimal.ZERO;
                    BigDecimal tax = base.multiply(taxRate).divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
                    subtotal = subtotal.add(base);
                    iva = iva.add(tax);
                }
            }
            if (subtotal.compareTo(BigDecimal.ZERO) == 0 && itemDTOs != null) {
                for (CreateInvoiceRequestDTO.ItemDTO item : itemDTOs) {
                    BigDecimal qty = item.quantity() != null ? item.quantity() : BigDecimal.ONE;
                    BigDecimal price = item.unitPrice() != null ? item.unitPrice() : BigDecimal.ZERO;
                    subtotal = subtotal.add(qty.multiply(price));
                }
            }
            BigDecimal propina = propinaValor != null ? propinaValor : BigDecimal.ZERO;
            BigDecimal total = subtotal.add(iva).add(propina);
            return new TotalsSnapshot(subtotal, iva, propina, total);
        }

        private record TotalsSnapshot(BigDecimal subtotal, BigDecimal iva, BigDecimal propina, BigDecimal total) {
        }

        private String formatFechaEmisionReferencia(String rawDate) {
            if (!StringUtils.hasText(rawDate)) {
                return java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(-5)).toString();
            }
            String value = rawDate.trim();
            if (value.length() == 10) {
                return value + "T00:00:00-05:00";
            }
            return value;
        }

        private String describeConceptoCredito(String codigo) {
            return switch (defaultIfBlank(codigo, "1")) {
                case "2" -> "Anulación de factura electrónica";
                case "3" -> "Rebaja  o descuento parcial o total";
                case "4" -> "Ajuste de precio";
                case "5" -> "Otros";
                default -> "Devolución parcial de los bienes y/o no aceptación parcial del servicio";
            };
        }

        private String describeConceptoDebito(String codigo) {
            return switch (codigo) {
                case "2" -> "Cobro de mayor valor";
                case "3" -> "Otros";
                default -> "Intereses";
            };
        }

        /** Etiqueta legible para UI (no usar en payload DIAN). */
        private String mapIdentificationType(String code) {
            if (code == null || code.isBlank()) {
                return "NIT";
            }
            return switch (code.trim()) {
                case "13" -> "CC";
                case "22" -> "CE";
                case "42" -> "PA";
                case "31" -> "NIT";
                default -> code;
            };
        }

        private String defaultIfBlank(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }

        public String getSociedadId() {
            return sociedadId;
        }

        public void setSociedadId(String sociedadId) {
            this.sociedadId = sociedadId;
        }

        public String getEmissionPointId() {
            return emissionPointId;
        }

        public void setEmissionPointId(String emissionPointId) {
            this.emissionPointId = emissionPointId;
        }

        public String getTipoOperacion() {
            return tipoOperacion;
        }

        public void setTipoOperacion(String tipoOperacion) {
            this.tipoOperacion = tipoOperacion;
        }

        public String getTipoDocumentoIdentidad() {
            return tipoDocumentoIdentidad;
        }

        public void setTipoDocumentoIdentidad(String tipoDocumentoIdentidad) {
            this.tipoDocumentoIdentidad = tipoDocumentoIdentidad;
        }

        public String getIdentificacion() {
            return identificacion;
        }

        public void setIdentificacion(String identificacion) {
            this.identificacion = identificacion;
        }

        public String getRazonSocial() {
            return razonSocial;
        }

        public void setRazonSocial(String razonSocial) {
            this.razonSocial = razonSocial;
        }

        public String getDireccion() {
            return direccion;
        }

        public void setDireccion(String direccion) {
            this.direccion = direccion;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public BigDecimal getPropinaValor() {
            return propinaValor;
        }

        public void setPropinaValor(BigDecimal propinaValor) {
            this.propinaValor = propinaValor;
        }

        public List<ManualItemForm> getItems() {
            return items;
        }

        public void setItems(List<ManualItemForm> items) {
            this.items = items;
        }

        public String getCufeReferencia() {
            return cufeReferencia;
        }

        public void setCufeReferencia(String cufeReferencia) {
            this.cufeReferencia = cufeReferencia;
        }

        public String getNumeroDocumentoReferencia() {
            return numeroDocumentoReferencia;
        }

        public void setNumeroDocumentoReferencia(String numeroDocumentoReferencia) {
            this.numeroDocumentoReferencia = numeroDocumentoReferencia;
        }

        public String getFechaEmisionReferencia() {
            return fechaEmisionReferencia;
        }

        public void setFechaEmisionReferencia(String fechaEmisionReferencia) {
            this.fechaEmisionReferencia = fechaEmisionReferencia;
        }

        public String getConceptoCredito() {
            return conceptoCredito;
        }

        public void setConceptoCredito(String conceptoCredito) {
            this.conceptoCredito = conceptoCredito;
        }
    }

    public static class ManualItemForm {
        private String codigo;
        private String descripcion;
        private BigDecimal cantidad;
        private BigDecimal precioUnitario;
        private BigDecimal porcentajeIva;

        public String getCodigo() {
            return codigo;
        }

        public void setCodigo(String codigo) {
            this.codigo = codigo;
        }

        public String getDescripcion() {
            return descripcion;
        }

        public void setDescripcion(String descripcion) {
            this.descripcion = descripcion;
        }

        public BigDecimal getCantidad() {
            return cantidad;
        }

        public void setCantidad(BigDecimal cantidad) {
            this.cantidad = cantidad;
        }

        public BigDecimal getPrecioUnitario() {
            return precioUnitario;
        }

        public void setPrecioUnitario(BigDecimal precioUnitario) {
            this.precioUnitario = precioUnitario;
        }

        public BigDecimal getPorcentajeIva() {
            return porcentajeIva;
        }

        public void setPorcentajeIva(BigDecimal porcentajeIva) {
            this.porcentajeIva = porcentajeIva;
        }
    }
}
