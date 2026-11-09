package com.zonak.portal.controller;

import com.zonak.portal.admin.AdminPortalRepository;
import com.zonak.portal.dashboard.PortalAnalyticsRepository;
import com.zonak.portal.dashboard.PortalDashboardExportService;
import com.zonak.portal.service.PortalSessionService;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
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
    private final PortalAnalyticsRepository portalAnalyticsRepository;
    private final PortalDashboardExportService dashboardExportService;
    private final PortalSessionService portalSessionService;

    public PortalDashboardController(
            AdminPortalRepository adminPortalRepository,
            PortalAnalyticsRepository portalAnalyticsRepository,
            PortalDashboardExportService dashboardExportService,
            PortalSessionService portalSessionService
    ) {
        this.adminPortalRepository = adminPortalRepository;
        this.portalAnalyticsRepository = portalAnalyticsRepository;
        this.dashboardExportService = dashboardExportService;
        this.portalSessionService = portalSessionService;
    }

    @GetMapping({"/", "/portal"})
    public String dashboard(HttpSession session, Model model) {
        model.addAttribute("username", session.getAttribute("username"));
        model.addAttribute("role", session.getAttribute("role"));
        model.addAttribute("tenantId", session.getAttribute("tenantId"));

        String tenantId = portalSessionService.resolveTenantId(session);
        DashboardData dashboardData = resolveDashboardKpis(tenantId);
        Map<String, Object> kpis = dashboardData.kpis();
        List<PortalAnalyticsRepository.MonthlyEmission> monthlyEmissions;
        boolean monthlyEmissionsAvailable = true;
        try {
            monthlyEmissions = portalAnalyticsRepository.monthlyEmissions(UUID.fromString(tenantId));
        } catch (Exception ignored) {
            monthlyEmissions = List.of();
            monthlyEmissionsAvailable = false;
        }
        long maxMonthlyEmissions = monthlyEmissions.stream()
                .mapToLong(PortalAnalyticsRepository.MonthlyEmission::total)
                .max()
                .orElse(0L);
        model.addAttribute("monthlyEmissions", monthlyEmissions);
        model.addAttribute("monthlyChartBars", monthlyEmissions.stream()
            .map(emission -> new MonthlyChartBar(
                emission.month(),
                emission.total(),
                maxMonthlyEmissions == 0 ? 0 : (int) Math.ceil(emission.total() * 100.0 / maxMonthlyEmissions),
                emission.total() > 0 ? "2px" : "0px"
            ))
            .toList());
        model.addAttribute("maxMonthlyEmissions", maxMonthlyEmissions);
        model.addAttribute("monthlyEmissionsAvailable", monthlyEmissionsAvailable);
        model.addAttribute("chartYear", LocalDate.now().getYear());
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
        model.addAttribute("emissionServiceStatus", dashboardData.localAvailable() ? "Consulta local" : "Sin conexión");
        model.addAttribute("receptionServiceStatus", dashboardData.localAvailable()
            ? (pendingReception > 0 ? "Con pendientes" : "Consulta local")
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
        DashboardExportData data = dashboardExportData(tenantId);
        Map<String, Object> kpis = data.kpis();

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
        appendCsvRow(csv, "Mes", "Documentos emitidos", "Año " + data.year());
        for (PortalAnalyticsRepository.MonthlyEmission emission : data.monthlyEmissions()) {
            appendCsvRow(csv, emission.month(), Long.toString(emission.total()), "");
        }
        appendCsvRow(csv, "", "", "");
        appendCsvRow(csv, "Factura", "Estado DIAN", "Fecha de creación", "Última actualización");
        for (PortalAnalyticsRepository.RecentActivity activity : data.activities()) {
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

    @GetMapping("/portal/dashboard/export/pdf")
    public ResponseEntity<byte[]> exportDashboardPdf(HttpSession session) {
        DashboardExportData data = dashboardExportData(portalSessionService.resolveTenantId(session));
        byte[] pdf = dashboardExportService.toPdf(
                data.year(), data.kpis(), data.monthlyEmissions(), data.activities(), data.monthlyEmissionsAvailable()
        );
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=dashboard-zona-k.pdf")
                .body(pdf);
    }

    @GetMapping("/portal/dashboard/export/excel")
    public ResponseEntity<byte[]> exportDashboardExcel(HttpSession session) {
        DashboardExportData data = dashboardExportData(portalSessionService.resolveTenantId(session));
        byte[] excel = dashboardExportService.toExcel(
                data.year(), data.kpis(), data.monthlyEmissions(), data.activities()
        );
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=dashboard-zona-k.xlsx")
                .body(excel);
    }

    private DashboardExportData dashboardExportData(String tenantId) {
        UUID tenantUuid = UUID.fromString(tenantId);
        List<PortalAnalyticsRepository.MonthlyEmission> monthlyEmissions;
        boolean monthlyEmissionsAvailable = true;
        try {
            monthlyEmissions = portalAnalyticsRepository.monthlyEmissions(tenantUuid);
        } catch (Exception ignored) {
            monthlyEmissions = List.of();
            monthlyEmissionsAvailable = false;
        }
        List<PortalAnalyticsRepository.RecentActivity> activities;
        try {
            activities = portalAnalyticsRepository.recentActivities(tenantUuid);
        } catch (Exception ignored) {
            activities = List.of();
        }
        return new DashboardExportData(
                resolveDashboardKpis(tenantId).kpis(),
                monthlyEmissions,
                activities,
                LocalDate.now().getYear(),
                monthlyEmissionsAvailable
        );
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

    private DashboardData resolveDashboardKpis(String tenantId) {
        try {
            return new DashboardData(
                    portalAnalyticsRepository.dashboardKpis(UUID.fromString(tenantId)),
                    true
            );
        } catch (Exception ignored) {
            return new DashboardData(PortalAnalyticsRepository.emptyKpis(), false);
        }
    }

    private record DashboardData(Map<String, Object> kpis, boolean localAvailable) {
    }

    private record MonthlyChartBar(String month, long total, int heightPercent, String minimumHeight) {
    }

        private record DashboardExportData(
            Map<String, Object> kpis,
            List<PortalAnalyticsRepository.MonthlyEmission> monthlyEmissions,
            List<PortalAnalyticsRepository.RecentActivity> activities,
            int year,
            boolean monthlyEmissionsAvailable
        ) {
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
