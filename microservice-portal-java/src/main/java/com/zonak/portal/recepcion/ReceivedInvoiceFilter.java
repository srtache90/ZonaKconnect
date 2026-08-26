package com.zonak.portal.recepcion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ReceivedInvoiceFilter(
        UUID sociedadId,
        LocalDate fromDate,
        LocalDate toDate,
        String estadoDian,
        String proveedor,
        String cufe,
        BigDecimal minTotal,
        BigDecimal maxTotal,
        Boolean openOnly,
        List<UUID> allowedEmissionPointIds,
        Boolean includeUnassigned,
        UUID assignedEmissionPointId
) {
    public static ReceivedInvoiceFilter ofSociedad(UUID sociedadId) {
        return new ReceivedInvoiceFilter(
                sociedadId, null, null, null, null, null, null, null, null, null, true, null
        );
    }
}
