package com.zonak.portal.recepcion;

import com.zonak.portal.mail.InvoiceMailDispatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RadianSupplierNotifyService {
    private static final Logger log = LoggerFactory.getLogger(RadianSupplierNotifyService.class);

    private final InvoiceMailDispatchService invoiceMailDispatchService;
    private final RadianEventRepository radianEventRepository;

    public RadianSupplierNotifyService(
            InvoiceMailDispatchService invoiceMailDispatchService,
            RadianEventRepository radianEventRepository
    ) {
        this.invoiceMailDispatchService = invoiceMailDispatchService;
        this.radianEventRepository = radianEventRepository;
    }

    public void notifyIfPossible(
            String tenantId,
            java.util.UUID companyId,
            java.util.UUID eventId,
            String supplierEmail,
            String eventLabel,
            String eventCode,
            String invoiceNumber,
            String cufe,
            String trackId,
            String estado
    ) {
        if (!StringUtils.hasText(supplierEmail)) {
            radianEventRepository.updateNotify(
                    companyId, eventId, "OMITIDO", "Sin correo de proveedor para notificar"
            );
            return;
        }
        try {
            String body = """
                    Se registró un evento RADIAN sobre su factura electrónica.

                    Evento: %s (%s)
                    Factura: %s
                    CUFE: %s
                    Estado DIAN: %s
                    TrackID: %s

                    Este mensaje es informativo. Conserve el TrackID para seguimiento ante la DIAN.
                    """.formatted(
                    eventLabel,
                    eventCode,
                    invoiceNumber,
                    cufe,
                    estado,
                    StringUtils.hasText(trackId) ? trackId : "n/d"
            );
            String result = invoiceMailDispatchService.sendPlainText(
                    tenantId,
                    supplierEmail.trim(),
                    "Evento RADIAN " + eventCode + " - factura " + invoiceNumber,
                    body
            );
            radianEventRepository.updateNotify(companyId, eventId, "ENVIADO", result);
        } catch (Exception ex) {
            log.warn("No se notificó al proveedor evento={}: {}", eventId, ex.getMessage());
            radianEventRepository.updateNotify(
                    companyId, eventId, "ERROR", ex.getMessage()
            );
        }
    }
}
