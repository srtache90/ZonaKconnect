package com.zonak.portal.controller;

import com.zonak.portal.dashboard.PortalAnalyticsRepository;
import com.zonak.portal.service.InvoiceClientService;
import com.zonak.portal.service.PortalSessionService;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class SearchPortalController {
    private static final Logger log = LoggerFactory.getLogger(SearchPortalController.class);

    private final InvoiceClientService invoiceClientService;
    private final PortalAnalyticsRepository portalAnalyticsRepository;
    private final PortalSessionService portalSessionService;

    public SearchPortalController(
            InvoiceClientService invoiceClientService,
            PortalAnalyticsRepository portalAnalyticsRepository,
            PortalSessionService portalSessionService
    ) {
        this.invoiceClientService = invoiceClientService;
        this.portalAnalyticsRepository = portalAnalyticsRepository;
        this.portalSessionService = portalSessionService;
    }

    @GetMapping("/portal/search")
    public String search(
            @RequestParam(required = false) String q,
            HttpSession session,
            Model model
    ) {
        String query = q == null ? "" : q.trim();
        String tenantId = portalSessionService.resolveTenantId(session);
        String emissionPointId = session.getAttribute("emissionPointId") != null
                ? session.getAttribute("emissionPointId").toString()
                : "";

        List<Map<String, Object>> results = List.of();
        if (StringUtils.hasText(query) && query.length() >= 2) {
            try {
                results = resolveSearchResults(query, tenantId, emissionPointId);
            } catch (RuntimeException ex) {
                log.error("No fue posible buscar documentos para tenant={} queryLength={} cause={}",
                        tenantId, query.length(), ex.getClass().getSimpleName());
                model.addAttribute("searchError", "No fue posible completar la búsqueda. Intente de nuevo.");
            }
        }

        model.addAttribute("q", query);
        model.addAttribute("results", results);
        model.addAttribute("navModule", "emision");
        model.addAttribute("navActive", "emision");
        return "portal/search";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resolveSearchResults(String q, String tenantId, String emissionPointId) {
        try {
            Map<String, Object> response = invoiceClientService
                    .searchDocuments(q, tenantId, emissionPointId)
                    .block(Duration.ofSeconds(4));
            if (response != null && response.get("results") instanceof List<?> list) {
                return (List<Map<String, Object>>) list;
            }
            log.warn("core-go devolvió una respuesta de búsqueda sin resultados válidos; se intentará la consulta local. tenant={} queryLength={}",
                    tenantId, q.length());
        } catch (Exception ignored) {
            log.warn("Falló la búsqueda en core-go; se intentará la consulta local. tenant={} queryLength={} cause={}",
                    tenantId, q.length(), ignored.getClass().getSimpleName());
        }
        return portalAnalyticsRepository.searchDocuments(UUID.fromString(tenantId), q);
    }
}
