package com.zonak.portal.controller;

import com.zonak.portal.admin.AdminPortalRepository;
import com.zonak.portal.dashboard.PortalAnalyticsRepository;
import com.zonak.portal.service.InvoiceClientService;
import com.zonak.portal.service.PortalSessionService;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class PortalDashboardController {
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
        Map<String, Object> kpis = resolveDashboardKpis(tenantId, emissionPointId);
        model.addAttribute("kpiEmittedToday", asLong(kpis.get("emitted_today")));
        model.addAttribute("kpiEmittedMonth", asLong(kpis.get("emitted_month")));
        model.addAttribute("kpiAccepted", asLong(kpis.get("accepted_dian")));
        model.addAttribute("kpiRejected", asLong(kpis.get("rejected_dian")));
        model.addAttribute("kpiPendingReception", asLong(kpis.get("pending_reception")));
        model.addAttribute("kpiSupport", asLong(kpis.get("support_documents")));
        model.addAttribute("kpiPayroll", asLong(kpis.get("payroll_documents")));
        model.addAttribute("kpis", kpis);
        return "portal/dashboard";
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

    private Map<String, Object> resolveDashboardKpis(String tenantId, String emissionPointId) {
        try {
            Map<String, Object> remote = invoiceClientService
                    .dashboardKpis(tenantId, emissionPointId)
                    .block(Duration.ofSeconds(4));
            if (remote != null && !remote.isEmpty()) {
                return remote;
            }
        } catch (Exception ignored) {
            // fallback local
        }
        try {
            return portalAnalyticsRepository.dashboardKpis(UUID.fromString(tenantId));
        } catch (Exception ignored) {
            return PortalAnalyticsRepository.emptyKpis();
        }
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
}
