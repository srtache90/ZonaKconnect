package com.zonak.portal.recepcion;

import com.zonak.portal.service.PortalSessionService;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/recepcion")
public class RecepcionRadianApiController {
    private final PortalSessionService portalSessionService;
    private final RecepcionEventService recepcionEventService;
    private final ReceivedInvoiceRepository receivedInvoiceRepository;

    public RecepcionRadianApiController(
            PortalSessionService portalSessionService,
            RecepcionEventService recepcionEventService,
            ReceivedInvoiceRepository receivedInvoiceRepository
    ) {
        this.portalSessionService = portalSessionService;
        this.recepcionEventService = recepcionEventService;
        this.receivedInvoiceRepository = receivedInvoiceRepository;
    }

    private UUID tenantId(HttpSession session, UUID invoiceId) {
        return receivedInvoiceRepository
                .findOwnedCompanyId(invoiceId, portalSessionService.resolveSociedadIds(session))
                .orElseGet(() -> UUID.fromString(portalSessionService.resolveTenantId(session)));
    }

    @PostMapping(value = "/{id}/acuse-085", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> acuse085(@PathVariable UUID id, HttpSession session) {
        UUID companyId = tenantId(session, id);
        return execute(() -> recepcionEventService.registrarAcuse085(companyId, id));
    }

    @PostMapping(value = "/{id}/recibo-bienes-086", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> reciboBienes086(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            HttpSession session
    ) {
        UUID companyId = tenantId(session, id);
        String recibidoPor = body != null ? body.get("recibidoPor") : null;
        String documentoRecibidor = body != null ? body.get("documentoRecibidor") : null;
        return execute(() -> recepcionEventService.registrarReciboBienes086(
                companyId, id, recibidoPor, documentoRecibidor
        ));
    }

    @PostMapping(value = "/{id}/aceptacion-087", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> aceptacion087(@PathVariable UUID id, HttpSession session) {
        UUID companyId = tenantId(session, id);
        return execute(() -> recepcionEventService.registrarAceptacion087(companyId, id));
    }

    @PostMapping(value = "/{id}/rechazo-088", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> rechazo088(
            @PathVariable UUID id,
            @RequestParam(name = "motivo_rechazo") String motivoRechazo,
            HttpSession session
    ) {
        UUID companyId = tenantId(session, id);
        return execute(() -> recepcionEventService.registrarRechazo088(companyId, id, motivoRechazo));
    }

    private ResponseEntity<String> execute(EventAction action) {
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(action.run());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(ex.getMessage());
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(ex.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Error al procesar el evento RADIAN: " + ex.getMessage());
        }
    }

    @FunctionalInterface
    private interface EventAction {
        String run();
    }
}
