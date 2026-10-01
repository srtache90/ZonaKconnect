package com.zonak.portal.controller;

import com.zonak.portal.admin.AdminPortalRepository;
import com.zonak.portal.dashboard.PortalAnalyticsRepository;
import com.zonak.portal.service.InvoiceClientService;
import com.zonak.portal.service.PortalSessionService;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class PortalDashboardController {
    private static final DateTimeFormatter EXPORT_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final AdminPortalRepository adminPortalRepository;
    private final InvoiceClientService invoiceClientService;
    private final PortalAnalyticsRepository portalAnalyticsRepository;
    private final PortalSessionService portalSessionService;

    public PortalDashboardController(
            AdminPortalRepository adminPortalRepository,
            InvoiceClientService invoiceClientService,
            PortalAnalyticsRepository portalAnalyticsRepository,
            PortalSessionService portalSessionService
    ) {
        this.adminPortalRepository = adminPortalRepository;
        this.invoiceClientService = invoiceClientService;
        this.portalAnalyticsRepository = portalAnalyticsRepository;
        this.portalSessionService = portalSessionService;
    }

    @GetMapping({"/", "/portal"})
    public String dashboard(HttpSession session, Model model) {
        model.addAttribute("username", session.getAttribute("username"));
        model.addAttribute("role", session.getAttribute("role"));
        model.addAttribute("tenantId", session.getAttribute("tenantId"));

        String tenantId = portalSessionService.resolveTenantId(session);
        String emissionPointId = session.getAttribute("emissionPointId") != null
                ? session.getAttribute("emissionPointId").toString()
                : "";
        DashboardData dashboardData = resolveDashboardKpis(tenantId, emissionPointId);
        Map<String, Object> kpis = dashboardData.kpis();
        List<PortalAnalyticsRepository.RecentActivity> actividadReciente;
        try {
            actividadReciente = portalAnalyticsRepository.recentActivities(UUID.fromString(tenantId));
        } catch (Exception ignored) {
            actividadReciente = List.of();
        }
        model.addAttribute("actividadReciente", actividadReciente);
        long accepted = asLong(kpis.get("accepted_dian"));
        long rejected = asLong(kpis.get("rejected_dian"));
        long pendingReception = asLong(kpis.get("pending_reception"));
        long emittedMonth = asLong(kpis.get("emitted_month"));
        model.addAttribute("emissionServiceStatus", dashboardData.remoteAvailable() ? "Operativo" : "Sin conexión");
        model.addAttribute("receptionServiceStatus", dashboardData.remoteAvailable()
            ? (pendingReception > 0 ? "Con pendientes" : "Operativo")
            : "Sin conexión");
        model.addAttribute("validationStatus", accepted + rejected > 0
            ? "Con resultados"
            : (emittedMonth > 0 ? "En proceso" : "Sin actividad"));
        model.addAttribute("kpiEmittedToday", asLong(kpis.get("emitted_today")));
        model.addAttribute("kpiEmittedMonth", emittedMonth);
        model.addAttribute("kpiAccepted", asLong(kpis.get("accepted_dian")));
        model.addAttribute("kpiRejected", asLong(kpis.get("rejected_dian")));
        model.addAttribute("kpiPendingReception", asLong(kpis.get("pending_reception")));
        model.addAttribute("kpiSupport", asLong(kpis.get("support_documents")));
        model.addAttribute("kpiPayroll", asLong(kpis.get("payroll_documents")));
        model.addAttribute("kpis", kpis);
        return "portal/dashboard";
    }

    @GetMapping("/portal/dashboard/export")
    public ResponseEntity<byte[]> exportDashboard(HttpSession session) {
        String tenantId = portalSessionService.resolveTenantId(session);
        String emissionPointId = session.getAttribute("emissionPointId") != null
                ? session.getAttribute("emissionPointId").toString()
                : "";
        Map<String, Object> kpis = resolveDashboardKpis(tenantId, emissionPointId).kpis();
        UUID tenantUuid = UUID.fromString(tenantId);
        List<PortalAnalyticsRepository.RecentActivity> activities;
        try {
            activities = portalAnalyticsRepository.recentActivities(tenantUuid);
        } catch (Exception ignored) {
            activities = List.of();
        }

        StringBuilder csv = new StringBuilder("\uFEFF");
        appendCsvRow(csv, "Sección", "Indicador", "Valor");
        appendCsvRow(csv, "KPI", "Documentos emitidos hoy", Long.toString(asLong(kpis.get("emitted_today"))));
        appendCsvRow(csv, "KPI", "Documentos emitidos este mes", Long.toString(asLong(kpis.get("emitted_month"))));
        appendCsvRow(csv, "KPI", "Aceptados por DIAN", Long.toString(asLong(kpis.get("accepted_dian"))));
        appendCsvRow(csv, "KPI", "Rechazados por DIAN", Long.toString(asLong(kpis.get("rejected_dian"))));
        appendCsvRow(csv, "KPI", "Pendientes de recepción", Long.toString(asLong(kpis.get("pending_reception"))));
        appendCsvRow(csv, "KPI", "Documentos soporte", Long.toString(asLong(kpis.get("support_documents"))));
        appendCsvRow(csv, "KPI", "Documentos de nómina", Long.toString(asLong(kpis.get("payroll_documents"))));
        appendCsvRow(csv, "", "", "");
        appendCsvRow(csv, "Factura", "Estado DIAN", "Fecha de creación", "Última actualización");
        for (PortalAnalyticsRepository.RecentActivity activity : activities) {
            appendCsvRow(
                    csv,
                    activity.documentNumber(),
                    activity.estadoDian(),
                    formatExportDate(activity.createdAt()),
                    formatExportDate(activity.updatedAt())
            );
        }

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=dashboard-zona-k.csv")
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/portal/configuraciones")
    public String configuraciones(Model model) {
        model.addAttribute("sociedades", adminPortalRepository.findSociedades());
        model.addAttribute("navModule", "configuracion");
        model.addAttribute("navActive", "general");
        return "portal/configuraciones";
    }

    @PostMapping("/portal/configuraciones/dian-ambiente")
    @PreAuthorize("hasRole('ADMIN')")
    public String updateDianAmbiente(
            @RequestParam UUID sociedadId,
            @RequestParam String dianAmbiente,
            RedirectAttributes redirectAttributes
    ) {
        adminPortalRepository.updateDianAmbiente(sociedadId, dianAmbiente);
        redirectAttributes.addFlashAttribute("success", "Ambiente DIAN actualizado correctamente");
        return "redirect:/portal/configuraciones";
    }

    private DashboardData resolveDashboardKpis(String tenantId, String emissionPointId) {
        try {
            Map<String, Object> remote = invoiceClientService
                    .dashboardKpis(tenantId, emissionPointId)
                    .block(Duration.ofSeconds(4));
            if (remote != null && !remote.isEmpty()) {
                return new DashboardData(remote, true);
            }
        } catch (Exception ignored) {
            // fallback local
        }
        try {
            return new DashboardData(
                    portalAnalyticsRepository.dashboardKpis(UUID.fromString(tenantId)),
                    false
            );
        } catch (Exception ignored) {
            return new DashboardData(PortalAnalyticsRepository.emptyKpis(), false);
        }
    }

    private record DashboardData(Map<String, Object> kpis, boolean remoteAvailable) {
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static String formatExportDate(OffsetDateTime value) {
        return value == null ? "" : value.format(EXPORT_DATE_FORMAT);
    }

    private static void appendCsvRow(StringBuilder csv, String... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                csv.append(';');
            }
            String value = values[index] == null ? "" : values[index];
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append('\n');
    }
}
