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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
public class PayrollPortalController {
    private final PortalSessionService portalSessionService;
    private final AdminPortalRepository adminPortalRepository;
    private final DocumentKindInvoiceRepository documentKindInvoiceRepository;
    private final InvoiceClientService invoiceClientService;

    public PayrollPortalController(
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

    @GetMapping("/portal/nomina-electronica")
    public String list(HttpSession session, Model model) {
        List<Sociedad> sociedades = portalSessionService.resolveSociedades(session);
        String selectedSociedadId = portalSessionService.resolveSelectedSociedadId(session, sociedades);
        List<DocumentKindInvoiceRow> documents = List.of();
        if (StringUtils.hasText(selectedSociedadId)) {
            documents = documentKindInvoiceRepository.findByKind(UUID.fromString(selectedSociedadId), "PAYROLL");
        }

        model.addAttribute("documents", documents);
        model.addAttribute("sociedades", sociedades);
        model.addAttribute("selectedSociedadId", selectedSociedadId);
        model.addAttribute("totalRegistros", documents.size());
        model.addAttribute("navModule", "nomina");
        model.addAttribute("navActive", "inicio");
        return "portal/nomina/index";
    }

    @GetMapping("/portal/nomina-electronica/nuevo")
    public String form(HttpSession session, Model model) {
        populateFormContext(session, model);
        model.addAttribute("defaultPeriodo", YearMonth.now().toString());
        model.addAttribute("navModule", "nomina");
        model.addAttribute("navActive", "nuevo");
        return "portal/nomina/form";
    }

    @PostMapping("/portal/nomina-electronica/emitir")
    public String emitir(
            @ModelAttribute PayrollForm form,
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
            return "redirect:/portal/nomina-electronica/nuevo";
        }

        if (!sociedadIds.contains(tenantUuid)) {
            redirectAttributes.addFlashAttribute("error", "La sociedad seleccionada no está autorizada para este usuario.");
            return "redirect:/portal/nomina-electronica/nuevo";
        }
        if (!adminPortalRepository.puntoVentaActivoPerteneceASociedad(emissionPointId, tenantUuid)) {
            redirectAttributes.addFlashAttribute("error", "El punto de venta seleccionado no pertenece a la sociedad activa.");
            return "redirect:/portal/nomina-electronica/nuevo";
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
                    .emitPayroll(payload, tenantUuid.toString(), emissionPointId.toString())
                    .block();
            String status = response != null && StringUtils.hasText(response.status())
                    ? response.status()
                    : "ENVIADO";
            redirectAttributes.addFlashAttribute(
                    "success",
                    "Nómina emitida. ID: " + (response != null ? response.id() : "") + " (" + status + ")"
            );
        } catch (InvoiceEmissionException | IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", readableError(ex));
            return "redirect:/portal/nomina-electronica/nuevo";
        }

        return "redirect:/portal/nomina-electronica";
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
                ? "No fue posible emitir la nómina electrónica"
                : ex.getMessage();
    }

    public static class PayrollForm {
        private String sociedadId;
        private String emissionPointId;
        private String tipoIdentificacion = "CC";
        private String numeroIdentificacion;
        private String nombres;
        private String apellidos;
        private BigDecimal sueldo;
        private String periodoNomina;
        private List<PayrollLineForm> devengados = new ArrayList<>();
        private List<PayrollLineForm> deducciones = new ArrayList<>();

        public Map<String, Object> toPayload(Sociedad sociedad) {
            if (!StringUtils.hasText(numeroIdentificacion) || !StringUtils.hasText(nombres)) {
                throw new IllegalArgumentException("Debe indicar identificación y nombres del trabajador.");
            }

            YearMonth periodo = parsePeriodo(periodoNomina);
            LocalDate fechaInicio = periodo.atDay(1);
            LocalDate fechaFin = periodo.atEndOfMonth();
            LocalDate fechaPago = fechaFin;

            List<Map<String, Object>> devengadoMaps = mapLines(devengados, "Sueldo");
            List<Map<String, Object>> deduccionMaps = mapLines(deducciones, "Deducción");
            if (devengadoMaps.isEmpty() && sueldo != null && sueldo.compareTo(BigDecimal.ZERO) > 0) {
                Map<String, Object> sueldoLine = new LinkedHashMap<>();
                sueldoLine.put("concepto", "Sueldo");
                sueldoLine.put("valor", sueldo.setScale(2, RoundingMode.HALF_UP));
                devengadoMaps.add(sueldoLine);
            }
            if (devengadoMaps.isEmpty()) {
                throw new IllegalArgumentException("Debe incluir al menos un concepto de devengado o un sueldo.");
            }

            BigDecimal totalDevengados = sumLines(devengadoMaps);
            BigDecimal totalDeducciones = sumLines(deduccionMaps);
            BigDecimal totalComprobante = totalDevengados.subtract(totalDeducciones).setScale(2, RoundingMode.HALF_UP);

            Map<String, Object> trabajador = new LinkedHashMap<>();
            trabajador.put("tipoIdentificacion", defaultIfBlank(tipoIdentificacion, "CC"));
            trabajador.put("numeroIdentificacion", trim(numeroIdentificacion));
            trabajador.put("nombres", trim(nombres));
            trabajador.put("apellidos", trim(apellidos));
            trabajador.put("sueldo", sueldo != null ? sueldo : totalDevengados);

            Map<String, Object> pago = new LinkedHashMap<>();
            pago.put("totalDevengados", totalDevengados);
            pago.put("totalDeducciones", totalDeducciones);
            pago.put("totalComprobante", totalComprobante);
            pago.put("fechaInicio", fechaInicio.toString());
            pago.put("fechaFin", fechaFin.toString());
            pago.put("fechaPago", fechaPago.toString());

            Map<String, Object> nomina = new LinkedHashMap<>();
            nomina.put("tipoDocumento", "NominaIndividual");
            nomina.put("numeroDocumento", "");
            nomina.put("fechaEmision", OffsetDateTime.now(ZoneOffset.ofHours(-5)).toString());
            nomina.put("periodoNomina", periodo.toString());
            nomina.put("trabajador", trabajador);
            nomina.put("pago", pago);
            nomina.put("devengados", devengadoMaps);
            nomina.put("deducciones", deduccionMaps);

            Map<String, Object> totals = new LinkedHashMap<>();
            totals.put("subtotal", totalDevengados);
            totals.put("total", totalComprobante);
            totals.put("totalDevengados", totalDevengados);
            totals.put("totalDeducciones", totalDeducciones);

            String ambiente = sociedad != null && StringUtils.hasText(sociedad.dianAmbiente())
                    ? sociedad.dianAmbiente()
                    : "Habilitacion";

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ambiente", ambiente);
            payload.put("nomina", nomina);
            payload.put("totals_jsonb", totals);
            payload.put("trabajador", trabajador);
            return payload;
        }

        private static YearMonth parsePeriodo(String value) {
            if (!StringUtils.hasText(value)) {
                return YearMonth.now();
            }
            String trimmed = value.trim();
            try {
                return YearMonth.parse(trimmed, DateTimeFormatter.ofPattern("yyyy-MM"));
            } catch (RuntimeException ex) {
                return YearMonth.now();
            }
        }

        private static List<Map<String, Object>> mapLines(List<PayrollLineForm> lines, String defaultConcepto) {
            List<Map<String, Object>> result = new ArrayList<>();
            if (lines == null) {
                return result;
            }
            for (PayrollLineForm line : lines) {
                if (line == null || line.getValor() == null || line.getValor().compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("concepto", StringUtils.hasText(line.getConcepto()) ? line.getConcepto().trim() : defaultConcepto);
                row.put("valor", line.getValor().setScale(2, RoundingMode.HALF_UP));
                result.add(row);
            }
            return result;
        }

        private static BigDecimal sumLines(List<Map<String, Object>> lines) {
            BigDecimal total = BigDecimal.ZERO;
            for (Map<String, Object> line : lines) {
                Object valor = line.get("valor");
                if (valor instanceof BigDecimal decimal) {
                    total = total.add(decimal);
                }
            }
            return total.setScale(2, RoundingMode.HALF_UP);
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

        public String getNombres() {
            return nombres;
        }

        public void setNombres(String nombres) {
            this.nombres = nombres;
        }

        public String getApellidos() {
            return apellidos;
        }

        public void setApellidos(String apellidos) {
            this.apellidos = apellidos;
        }

        public BigDecimal getSueldo() {
            return sueldo;
        }

        public void setSueldo(BigDecimal sueldo) {
            this.sueldo = sueldo;
        }

        public String getPeriodoNomina() {
            return periodoNomina;
        }

        public void setPeriodoNomina(String periodoNomina) {
            this.periodoNomina = periodoNomina;
        }

        public List<PayrollLineForm> getDevengados() {
            return devengados;
        }

        public void setDevengados(List<PayrollLineForm> devengados) {
            this.devengados = devengados;
        }

        public List<PayrollLineForm> getDeducciones() {
            return deducciones;
        }

        public void setDeducciones(List<PayrollLineForm> deducciones) {
            this.deducciones = deducciones;
        }
    }

    public static class PayrollLineForm {
        private String concepto;
        private BigDecimal valor;

        public String getConcepto() {
            return concepto;
        }

        public void setConcepto(String concepto) {
            this.concepto = concepto;
        }

        public BigDecimal getValor() {
            return valor;
        }

        public void setValor(BigDecimal valor) {
            this.valor = valor;
        }
    }
}
