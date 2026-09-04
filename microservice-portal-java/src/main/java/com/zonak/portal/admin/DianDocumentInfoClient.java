package com.zonak.portal.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Service
public class DianDocumentInfoClient {
    private final WebClient dianNetWebClient;
    private final ObjectMapper objectMapper;
    private final DianCertificateProvisioningService certificateProvisioningService;

    public DianDocumentInfoClient(
            @Qualifier("dianNetWebClient") WebClient dianNetWebClient,
            ObjectMapper objectMapper,
            DianCertificateProvisioningService certificateProvisioningService
    ) {
        this.dianNetWebClient = dianNetWebClient;
        this.objectMapper = objectMapper;
        this.certificateProvisioningService = certificateProvisioningService;
    }

    public JsonNode consultarEventosPorCufe(SociedadDianContext context, String cufe) {
        if (context == null) {
            throw new IllegalArgumentException("Contexto DIAN de la sociedad requerido");
        }
        if (!StringUtils.hasText(cufe)) {
            throw new IllegalArgumentException("CUFE/CUDE requerido para consultar eventos RADIAN");
        }
        final SociedadDianContext resolved = certificateProvisioningService.ensureAvailable(context);
        if (!StringUtils.hasText(resolved.s3CertificateKey()) || !StringUtils.hasText(resolved.secretsManagerPasswordKey())) {
            throw new IllegalStateException(
                    "La sociedad no tiene certificado DIAN provisionado en S3/Secrets Manager. "
                            + "Cargue el certificado en Configuración > Certificados."
            );
        }

        try {
            return dianNetWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/dian/document-info")
                            .queryParam("uuid", cufe.trim())
                            .queryParam("ambiente", DianResolutionClient.normalizeAmbiente(resolved.ambiente()))
                            .build())
                    .header("X-Tenant-ID", resolved.sociedadId().toString())
                    .header("X-Cert-S3-Key", resolved.s3CertificateKey())
                    .header("X-Cert-Password-Secret-Key", resolved.secretsManagerPasswordKey())
                    .header("X-DIAN-Ambiente", DianResolutionClient.normalizeAmbiente(resolved.ambiente()))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (WebClientResponseException ex) {
            String detail = extractError(ex.getResponseBodyAsString());
            throw new IllegalStateException(
                    "DIAN_NET no pudo consultar eventos RADIAN (" + ex.getStatusCode().value() + "): "
                            + (detail != null ? detail : ex.getMessage()),
                    ex
            );
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible consultar eventos RADIAN en DIAN: " + ex.getMessage(), ex);
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
            JsonNode statusDescription = root.path("statusDescription");
            if (!statusDescription.isMissingNode() && !statusDescription.isNull()) {
                return statusDescription.asText();
            }
        } catch (Exception ignored) {
            return body;
        }
        return body;
    }
}
