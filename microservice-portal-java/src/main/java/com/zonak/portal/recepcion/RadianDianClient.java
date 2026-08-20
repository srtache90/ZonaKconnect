package com.zonak.portal.recepcion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Service
public class RadianDianClient {
    private final WebClient dianNetWebClient;
    private final ObjectMapper objectMapper;

    public RadianDianClient(
            @Qualifier("dianNetWebClient") WebClient dianNetWebClient,
            ObjectMapper objectMapper
    ) {
        this.dianNetWebClient = dianNetWebClient;
        this.objectMapper = objectMapper;
    }

    public JsonNode sendEvent(Map<String, Object> body) {
        try {
            return dianNetWebClient.post()
                    .uri("/api/v1/events/radian")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (WebClientResponseException ex) {
            String detail = ex.getResponseBodyAsString();
            String readable = extractErrores(detail);
            throw new IllegalStateException(
                    "DIAN_NET rechazó el evento RADIAN (" + ex.getStatusCode().value() + "): "
                            + (readable != null ? readable
                            : (detail == null || detail.isBlank() ? ex.getMessage() : detail)),
                    ex
            );
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible enviar el evento RADIAN a DIAN_NET: " + ex.getMessage(), ex);
        }
    }

    private String extractErrores(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode errores = root.path("errores");
            if (errores.isArray() && errores.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode item : errores) {
                    if (sb.length() > 0) {
                        sb.append(" | ");
                    }
                    sb.append(item.asText());
                }
                return sb.toString();
            }
            String status = root.path("status").asText(null);
            String message = root.path("statusMessage").asText(null);
            if (status != null || message != null) {
                return (status == null ? "" : status) + (message == null || message.isBlank() ? "" : ": " + message);
            }
        } catch (Exception ignored) {
            // body no JSON
        }
        return null;
    }
}
