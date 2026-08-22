package com.zonak.portal.recepcion;

import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Cliente de ingestión de recepción contra core-go (IMAP + import XML + webhook).
 */
@Service
public class ReceptionIngestClient {
    private final WebClient coreGoWebClient;

    public ReceptionIngestClient(@Qualifier("coreGoWebClient") WebClient coreGoWebClient) {
        this.coreGoWebClient = coreGoWebClient;
    }

    public SyncResult syncImap(UUID companyId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = coreGoWebClient.post()
                    .uri("/api/v1/reception/sync-imap")
                    .header("X-Tenant-ID", companyId.toString())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            return mapSync(body);
        } catch (WebClientResponseException ex) {
            throw new IllegalStateException(readable(ex), ex);
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible sincronizar IMAP vía core-go: " + ex.getMessage(), ex);
        }
    }

    public String testImap(UUID companyId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = coreGoWebClient.post()
                    .uri("/api/v1/reception/test-imap")
                    .header("X-Tenant-ID", companyId.toString())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (body == null) {
                return "Conexión IMAP OK (sin detalle).";
            }
            Object message = body.get("message");
            return message == null ? "Conexión IMAP OK." : message.toString();
        } catch (WebClientResponseException ex) {
            throw new IllegalStateException(readable(ex), ex);
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible probar IMAP vía core-go: " + ex.getMessage(), ex);
        }
    }

    public int importXml(UUID companyId, byte[] content, String fileName, String source) {
        try {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            String name = StringUtils.hasText(fileName) ? fileName : "upload.xml";
            builder.part("archivo", new ByteArrayResource(content) {
                @Override
                public String getFilename() {
                    return name;
                }
            }).filename(name);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = coreGoWebClient.post()
                    .uri("/api/v1/reception/import-xml")
                    .header("X-Tenant-ID", companyId.toString())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (body == null) {
                return 0;
            }
            Object imported = body.get("imported");
            if (imported instanceof Number number) {
                return number.intValue();
            }
            return 0;
        } catch (WebClientResponseException ex) {
            throw new IllegalStateException(readable(ex), ex);
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible importar XML vía core-go: " + ex.getMessage(), ex);
        }
    }

    private SyncResult mapSync(Map<String, Object> body) {
        if (body == null) {
            return new SyncResult(0, 0, 0, 0, "Sin respuesta del core");
        }
        int messages = intVal(body.get("messages"));
        int xmlFound = intVal(body.get("xml_found"));
        int imported = intVal(body.get("imported"));
        int skipped = intVal(body.get("skipped"));
        String summary = body.get("summary") == null
                ? ("Sync IMAP core: mensajes=" + messages + " xml=" + xmlFound
                + " importados=" + imported + " omitidos=" + skipped)
                : body.get("summary").toString();
        return new SyncResult(messages, xmlFound, imported, skipped, summary);
    }

    private static int intVal(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private static String readable(WebClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        if (StringUtils.hasText(body)) {
            return body;
        }
        return ex.getMessage();
    }

    public record SyncResult(int messages, int xmlFound, int imported, int skipped, String summary) {
    }
}
