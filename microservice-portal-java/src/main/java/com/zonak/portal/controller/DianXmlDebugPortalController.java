package com.zonak.portal.controller;

import com.zonak.portal.service.DianXmlDebugClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Controller
@PreAuthorize("hasRole('ADMIN')")
public class DianXmlDebugPortalController {
    private static final String DEFAULT_STAGE = "before-sign";

    private final DianXmlDebugClient dianXmlDebugClient;

    public DianXmlDebugPortalController(DianXmlDebugClient dianXmlDebugClient) {
        this.dianXmlDebugClient = dianXmlDebugClient;
    }

    @GetMapping("/portal/debug/dian/xml")
    public String latestXml(
            @RequestParam(defaultValue = DEFAULT_STAGE) String stage,
            Model model
    ) {
        String safeStage = normalizeStage(stage);
        model.addAttribute("stage", safeStage);

        try {
            Map<String, Object> metadata = dianXmlDebugClient.latestMetadata().block();
            String xml = dianXmlDebugClient.latestXml(safeStage).block();

            model.addAttribute("metadata", metadata);
            model.addAttribute("xml", xml);
        } catch (WebClientResponseException.NotFound ex) {
            model.addAttribute(
                    "error",
                    "DIAN_NET todavía no tiene XML disponible para esta etapa. Emite una factura o importa un XML primero."
            );
        } catch (RuntimeException ex) {
            model.addAttribute("error", "No fue posible consultar DIAN_NET: " + ex.getMessage());
        }

        model.addAttribute("navModule", "emision");
        model.addAttribute("navActive", "emision");
        return "portal/debug/dian-xml";
    }

    @GetMapping("/portal/debug/dian/xml/download")
    public ResponseEntity<String> downloadLatestXml(
            @RequestParam(defaultValue = DEFAULT_STAGE) String stage
    ) {
        String safeStage = normalizeStage(stage);
        String xml = dianXmlDebugClient.latestXml(safeStage).block();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("dian-net-" + safeStage + ".xml", StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(xml);
    }

    private static String normalizeStage(String stage) {
        return switch (stage) {
            case "original", "before-sign", "signed" -> stage;
            default -> DEFAULT_STAGE;
        };
    }
}
