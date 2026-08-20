package com.zonak.portal.mail;

import com.zonak.portal.recepcion.ReceptionIngestClient;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Fachada de ingestión de recepción. La lectura IMAP / import XML vive en core-go;
 * el portal solo orquesta la UI y los eventos RADIAN.
 */
@Service
public class MailReceptionSyncService {
    private final ReceptionIngestClient receptionIngestClient;

    public MailReceptionSyncService(ReceptionIngestClient receptionIngestClient) {
        this.receptionIngestClient = receptionIngestClient;
    }

    public SyncResult syncInbox(UUID companyId) {
        ReceptionIngestClient.SyncResult result = receptionIngestClient.syncImap(companyId);
        return new SyncResult(result.messages(), result.xmlFound(), result.imported(), result.skipped(), result.summary());
    }

    public String testIncomingConnection(UUID companyId) {
        return receptionIngestClient.testImap(companyId);
    }

    public int importXmlDocuments(UUID companyId, byte[] content, String fileName, String source) {
        return receptionIngestClient.importXml(companyId, content, fileName, source);
    }

    public record SyncResult(int messages, int xmlFound, int imported, int skipped, String coreSummary) {
        public String summary() {
            if (coreSummary != null && !coreSummary.isBlank()) {
                return coreSummary;
            }
            return "Sincronización completada vía core-go. Correos revisados: " + messages
                    + ". XML encontrados: " + xmlFound
                    + ". Documentos importados: " + imported
                    + ". Omitidos: " + skipped + ".";
        }
    }
}
