package com.zonak.portal.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record EmissionRadianReportRow(
        UUID invoiceId,
        String documentKind,
        String documentKindLabel,
        String facturaNumero,
        String notaCreditoNumero,
        String notaDebitoNumero,
        String documentoNumero,
        OffsetDateTime fechaEmision,
        String cufe,
        BigDecimal valorTotal,
        String estadoDian,
        List<RadianEventSummary> radianEvents
) {
    public record RadianEventSummary(
            String eventCode,
            String eventLabel,
            String estado,
            OffsetDateTime createdAt
    ) {
        public String display() {
            return eventCode + " - " + eventLabel + " (" + estado + ")";
        }
    }

    public String radianEventsText() {
        if (radianEvents == null || radianEvents.isEmpty()) {
            return "Sin eventos";
        }
        return radianEvents.stream()
                .map(RadianEventSummary::display)
                .reduce((left, right) -> left + " | " + right)
                .orElse("Sin eventos");
    }
}
