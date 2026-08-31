package com.zonak.portal.reports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zonak.portal.admin.DianDocumentInfoClient;
import com.zonak.portal.admin.SociedadDianContext;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class EmissionRadianSyncService {
    private static final Logger log = LoggerFactory.getLogger(EmissionRadianSyncService.class);

    private final EmissionRadianSyncRepository syncRepository;
    private final DianDocumentInfoClient dianDocumentInfoClient;
    private final ObjectMapper objectMapper;

    public EmissionRadianSyncService(
            EmissionRadianSyncRepository syncRepository,
            DianDocumentInfoClient dianDocumentInfoClient,
            ObjectMapper objectMapper
    ) {
        this.syncRepository = syncRepository;
        this.dianDocumentInfoClient = dianDocumentInfoClient;
        this.objectMapper = objectMapper;
    }

    public SyncResult syncFromDian(
            SociedadDianContext context,
            UUID tenantId,
            UUID emissionPointId,
            LocalDate fromDate,
            LocalDate toDate,
            String documentKind,
            String estadoDian
    ) {
        if (context == null) {
            throw new IllegalStateException("No se encontró contexto DIAN para la sociedad seleccionada.");
        }

        List<EmissionRadianSyncRepository.SyncCandidate> candidates = syncRepository.findCandidatesForSync(
                tenantId,
                emissionPointId,
                fromDate,
                toDate,
                documentKind,
                estadoDian
        );

        int syncedDocuments = 0;
        int failedDocuments = 0;
        int totalEvents = 0;
        List<String> errors = new ArrayList<>();
        OffsetDateTime syncedAt = OffsetDateTime.now();

        for (EmissionRadianSyncRepository.SyncCandidate candidate : candidates) {
            if (!StringUtils.hasText(candidate.cufe())) {
                continue;
            }
            try {
                JsonNode response = dianDocumentInfoClient.consultarEventosPorCufe(context, candidate.cufe());
                ArrayNode events = parseEvents(response);
                ObjectNode dianStatus = objectMapper.createObjectNode();
                dianStatus.put("statusCode", text(response, "statusCode", ""));
                dianStatus.put("statusDescription", text(response, "statusDescription", ""));
                dianStatus.put("documentUuid", text(response, "documentUuid", candidate.cufe()));

                syncRepository.persistDianRadianEvents(
                        tenantId,
                        candidate.id(),
                        events.toString(),
                        dianStatus.toString(),
                        syncedAt
                );
                syncedDocuments++;
                totalEvents += events.size();
            } catch (Exception ex) {
                failedDocuments++;
                String message = candidate.documentNumber() + ": " + ex.getMessage();
                errors.add(message);
                log.warn("Fallo sync RADIAN DIAN para {} ({}): {}", candidate.documentNumber(), candidate.cufe(), ex.getMessage());
            }
        }

        return new SyncResult(
                candidates.size(),
                syncedDocuments,
                failedDocuments,
                totalEvents,
                errors
        );
    }

    private ArrayNode parseEvents(JsonNode response) {
        ArrayNode events = objectMapper.createArrayNode();
        JsonNode items = response.path("events");
        if (!items.isArray()) {
            return events;
        }

        OffsetDateTime now = OffsetDateTime.now();
        for (JsonNode item : items) {
            ObjectNode event = objectMapper.createObjectNode();
            event.put("code", text(item, "code", ""));
            event.put("label", text(item, "label", text(item, "descripcion", "Evento RADIAN")));
            event.put("estado", text(item, "estado", "REGISTRADO"));
            event.put("at", now.toString());
            String eventUuid = text(item, "eventUuid", "");
            if (StringUtils.hasText(eventUuid)) {
                event.put("eventUuid", eventUuid);
            }
            event.put("source", "DIAN_GET_DOCUMENT_INFO");
            events.add(event);
        }
        return events;
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null || node.isMissingNode()) {
            return fallback;
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? fallback : value.asText(fallback);
    }

    public record SyncResult(
            int candidates,
            int syncedDocuments,
            int failedDocuments,
            int totalEvents,
            List<String> errors
    ) {
        public String summaryMessage() {
            StringBuilder message = new StringBuilder();
            message.append("Sincronización DIAN: ")
                    .append(syncedDocuments)
                    .append(" documento(s) actualizado(s), ")
                    .append(totalEvents)
                    .append(" evento(s) encontrado(s)");
            if (failedDocuments > 0) {
                message.append(", ")
                        .append(failedDocuments)
                        .append(" error(es)");
            }
            if (candidates == 0) {
                return "No hay documentos con CUFE/CUDE en el rango seleccionado.";
            }
            return message.toString();
        }
    }
}
