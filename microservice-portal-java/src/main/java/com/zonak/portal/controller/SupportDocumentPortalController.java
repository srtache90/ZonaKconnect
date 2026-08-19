package com.zonak.portal.controller;

import com.zonak.portal.admin.AdminPortalRepository;
import com.zonak.portal.admin.PuntoVenta;
import com.zonak.portal.admin.Sociedad;
import com.zonak.portal.dto.DocumentKindInvoiceRow;
import com.zonak.portal.dto.InvoiceResponseDTO;
import com.zonak.portal.exception.InvoiceEmissionException;
import com.zonak.portal.reports.DocumentKindInvoiceRepository;
import com.zonak.portal.service.InvoiceClientService;
import com.zonak.portal.service.PortalSessionService;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class SupportDocumentPortalController {
    private final PortalSessionService portalSessionService;
    private final AdminPortalRepository adminPortalRepository;
    private final DocumentKindInvoiceRepository documentKindInvoiceRepository;
    private final InvoiceClientService invoiceClientService;

    public SupportDocumentPortalController(
            PortalSessionService portalSessionService,
            AdminPortalRepository adminPortalRepository,
            DocumentKindInvoiceRepository documentKindInvoiceRepository,
            InvoiceClientService invoiceClientService
    ) {
        this.portalSessionService = portalSessionService;
        this.adminPortalRepository = adminPortalRepository;
        this.documentKindInvoiceRepository = documentKindInvoiceRepository;
        this.invoiceClientService = invoiceClientService;
    }

    @GetMapping("/portal/documento-soporte")
    public String list(HttpSession session, Model model) {
        List<Sociedad> sociedades = portalSessionService.resolveSociedades(session);
        String selectedSociedadId = portalSessionService.resolveSelectedSociedadId(session, sociedades);
        List<DocumentKindInvoiceRow> documents = List.of();
        if (StringUtils.hasText(selectedSociedadId)) {
            documents = documentKindInvoiceRepository.findByKind(UUID.fromString(selectedSociedadId), "SUPPORT");
        }

        model.addAttribute("documents", documents);
        model.addAttribute("sociedades", sociedades);
        model.addAttribute("selectedSociedadId", selectedSociedadId);
        model.addAttribute("totalRegistros", documents.size());
        model.addAttribute("navModule", "soporte");
        model.addAttribute("navActive", "inicio");
        return "portal/documento-soporte/index";
    }

    @GetMapping("/portal/documento-soporte/nuevo")
    public String form(HttpSession session, Model model) {
        populateFormContext(session, model);
        model.addAttribute("navModule", "soporte");
        model.addAttribute("navActive", "nuevo");
        return "portal/documento-soporte/form";
    }

    @PostMapping("/portal/documento-soporte/emitir")
    public String emitir(
            @ModelAttribute SupportDocumentForm form,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        List<UUID> sociedadIds = portalSessionService.resolveSociedadIds(session);
        UUID tenantUuid;
        UUID emissionPointId;

        try {
            tenantUuid = UUID.fromString(form.getSociedadId());
            emissionPointId = UUID.fromString(form.getEmissionPointId());
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", "Debe seleccionar una sociedad y un punto de venta válidos.");
            return "redirect:/portal/documento-soporte/nuevo";
        }

        if (!sociedadIds.contains(tenantUuid)) {
            redirectAttributes.addFlashAttribute("error", "La sociedad seleccionada no está autorizada para este usuario.");
            return "redirect:/portal/documento-soporte/nuevo";
        }
        if (!adminPortalRepository.puntoVentaActivoPerteneceASociedad(emissionPointId, tenantUuid)) {
            redirectAttributes.addFlashAttribute("error", "El punto de venta seleccionado no pertenece a la sociedad activa.");
            return "redirect:/portal/documento-soporte/nuevo";
        }

        session.setAttribute("tenantId", tenantUuid.toString());
        session.setAttribute("emissionPointId", emissionPointId.toString());

        Sociedad sociedad = portalSessionService.resolveSociedades(session).stream()
                .filter(s -> s.id().equals(tenantUuid))
                .findFirst()
                .orElse(null);

        try {
            Map<String, Object> payload = form.toPayload(sociedad);
            InvoiceResponseDTO response = invoiceClientService
                    .emitSupportDocument(payload, tenantUuid.toString(), emissionPointId.toString())
                    .block();
            String status = response != null && StringUtils.hasText(response.status())
                    ? response.status()
                    : "ENVIADO";
            redirectAttributes.addFlashAttribute(
                    "success",
                    "Documento soporte emitido. ID: " + (response != null ? response.id() : "") + " (" + status + ")"
            );
        } catch (InvoiceEmissionException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", readableError(ex));
            return "redirect:/portal/documento-soporte/nuevo";
        }

        return "redirect:/portal/documento-soporte";
    }

    private void populateFormContext(HttpSession session, Model model) {
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
    }

    private String readableError(RuntimeException ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? "No fue posible emitir el documento soporte"
                : ex.getMessage();
    }

    public static class SupportDocumentForm {
        private String sociedadId;
        private String emissionPointId;
        private String tipoIdentificacion = "NIT";
        private String numeroIdentificacion;
        private String razonSocial;
        private String email;
        private String observaciones;
        private List<SupportItemForm> items = new ArrayList<>();

        public Map<String, Object> toPayload(Sociedad sociedad) {
            List<Map<String, Object>> itemMaps = new ArrayList<>();
            BigDecimal subtotal = BigDecimal.ZERO;
            if (items != null) {
                for (SupportItemForm item : items) {
                    if (item == null || !StringUtils.hasText(item.getDescripcion())) {
                        continue;
                    }
                    BigDecimal cantidad = item.getCantidad() != null ? item.getCantidad() : BigDecimal.ONE;
                    BigDecimal precio = item.getPrecioUnitario() != null ? item.getPrecioUnitario() : BigDecimal.ZERO;
                    BigDecimal line = cantidad.multiply(precio).setScale(2, RoundingMode.HALF_UP);
                    subtotal = subtotal.add(line);

                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("codigo", StringUtils.hasText(item.getCodigo()) ? item.getCodigo().trim() : "DS-001");
                    row.put("descripcion", item.getDescripcion().trim());
                    row.put("cantidad", cantidad);
                    row.put("precioUnitario", precio);
                    itemMaps.add(row);
                }
            }
            if (itemMaps.isEmpty()) {
                throw new IllegalArgumentException("Debe incluir al menos un ítem con descripción.");
            }

            Map<String, Object> totales = Map.of("subtotal", subtotal, "total", subtotal);
            Map<String, Object> cliente = new LinkedHashMap<>();
            cliente.put("tipoIdentificacion", defaultIfBlank(tipoIdentificacion, "NIT"));
            cliente.put("numeroIdentificacion", trim(numeroIdentificacion));
            cliente.put("razonSocial", trim(razonSocial));
            cliente.put("email", trim(email));

            Map<String, Object> documentoSoporte = new LinkedHashMap<>();
            documentoSoporte.put("tipoDocumento", "DS");
            documentoSoporte.put("numeroDocumento", "will be assigned by core");
            documentoSoporte.put("fechaEmision", OffsetDateTime.now(ZoneOffset.ofHours(-5)).toString());
            documentoSoporte.put("moneda", "COP");
            documentoSoporte.put("cliente", cliente);
            documentoSoporte.put("items", itemMaps);
            documentoSoporte.put("totales", totales);
            documentoSoporte.put("observaciones", trim(observaciones));

            Map<String, Object> proveedor = new LinkedHashMap<>();
            proveedor.put("razon_social", sociedad != null ? sociedad.razonSocial() : trim(razonSocial));
            proveedor.put("nit", sociedad != null ? sociedad.nit() : trim(numeroIdentificacion));

            String ambiente = sociedad != null && StringUtils.hasText(sociedad.dianAmbiente())
                    ? sociedad.dianAmbiente()
                    : "Habilitacion";

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ambiente", ambiente);
            payload.put("documentoSoporte", documentoSoporte);
            payload.put("totals_jsonb", totales);
            payload.put("proveedor", proveedor);
            return payload;
        }

        private static String defaultIfBlank(String value, String fallback) {
            return StringUtils.hasText(value) ? value.trim() : fallback;
        }

        private static String trim(String value) {
            return value == null ? "" : value.trim();
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

        public String getTipoIdentificacion() {
            return tipoIdentificacion;
        }

        public void setTipoIdentificacion(String tipoIdentificacion) {
            this.tipoIdentificacion = tipoIdentificacion;
        }

        public String getNumeroIdentificacion() {
            return numeroIdentificacion;
        }

        public void setNumeroIdentificacion(String numeroIdentificacion) {
            this.numeroIdentificacion = numeroIdentificacion;
        }

        public String getRazonSocial() {
            return razonSocial;
        }

        public void setRazonSocial(String razonSocial) {
            this.razonSocial = razonSocial;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getObservaciones() {
            return observaciones;
        }

        public void setObservaciones(String observaciones) {
            this.observaciones = observaciones;
        }

        public List<SupportItemForm> getItems() {
            return items;
        }

        public void setItems(List<SupportItemForm> items) {
            this.items = items;
        }
    }

    public static class SupportItemForm {
        private String codigo;
        private String descripcion;
        private BigDecimal cantidad;
        private BigDecimal precioUnitario;

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
    }
}
