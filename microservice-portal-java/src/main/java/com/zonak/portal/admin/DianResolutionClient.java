package com.zonak.portal.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
@Service
public class DianResolutionClient {
    private final WebClient dianNetWebClient;
    private final ObjectMapper objectMapper;

    public DianResolutionClient(
            @Qualifier("dianNetWebClient") WebClient dianNetWebClient,
            ObjectMapper objectMapper
    ) {
        this.dianNetWebClient = dianNetWebClient;
        this.objectMapper = objectMapper;
    }

    public JsonNode consultarResoluciones(
            SociedadDianContext context,
            String resolutionNumber,
            String prefix
    ) {
        if (context == null) {
            throw new IllegalArgumentException("Contexto DIAN de la sociedad requerido");
        }
        if (!StringUtils.hasText(context.nit())) {
            throw new IllegalArgumentException("La sociedad no tiene NIT configurado");
        }
        if (!StringUtils.hasText(context.softwareId())) {
            throw new IllegalArgumentException("La sociedad no tiene Software ID DIAN configurado");
        }
        if (!StringUtils.hasText(context.s3CertificateKey()) || !StringUtils.hasText(context.secretsManagerPasswordKey())) {
            throw new IllegalStateException(
                    "La sociedad no tiene certificado DIAN provisionado en S3/Secrets Manager. "
                            + "Cargue el certificado en Configuración > Certificados."
            );
        }

        try {
            return dianNetWebClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder
                                .path("/api/v1/dian/numbering-ranges")
                                .queryParam("nit", normalizeNit(context.nit()))
                                .queryParam("softwareId", context.softwareId().trim())
                                .queryParam("ambiente", normalizeAmbiente(context.ambiente()));
                        if (StringUtils.hasText(resolutionNumber)) {
                            builder = builder.queryParam("resolutionNumber", resolutionNumber.trim());
                        }
                        if (StringUtils.hasText(prefix)) {
                            builder = builder.queryParam("prefix", prefix.trim());
                        }
                        return builder.build();
                    })
                    .header("X-Tenant-ID", context.sociedadId().toString())
                    .header("X-Cert-S3-Key", context.s3CertificateKey())
                    .header("X-Cert-Password-Secret-Key", context.secretsManagerPasswordKey())
                    .header("X-DIAN-Ambiente", normalizeAmbiente(context.ambiente()))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (WebClientResponseException ex) {
            String detail = extractError(ex.getResponseBodyAsString());
            throw new IllegalStateException(
                    "DIAN_NET no pudo consultar resoluciones (" + ex.getStatusCode().value() + "): "
                            + (detail != null ? detail : ex.getMessage()),
                    ex
            );
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible consultar resoluciones DIAN: " + ex.getMessage(), ex);
        }
    }

    private String extractError(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                return error.asText();
            }
            JsonNode operationDescription = root.path("operationDescription");
            if (!operationDescription.isMissingNode() && !operationDescription.isNull()) {
                return operationDescription.asText();
            }
        } catch (Exception ignored) {
            return body;
        }
        return body;
    }

    static String normalizeNit(String nit) {
        if (!StringUtils.hasText(nit)) {
            return "";
        }
        String digits = nit.replaceAll("\\D", "");
        if (digits.length() > 9) {
            return digits.substring(0, digits.length() - 1);
        }
        return digits;
    }

    static String normalizeAmbiente(String ambiente) {
        if (!StringUtils.hasText(ambiente)) {
            return "Habilitacion";
        }
        String value = ambiente.trim();
        if ("Produccion".equalsIgnoreCase(value) || "Producción".equalsIgnoreCase(value)) {
            return "Produccion";
        }
        if ("Mock".equalsIgnoreCase(value)) {
            return "Mock";
        }
        return "Habilitacion";
    }
}
