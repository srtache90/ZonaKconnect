package com.zonak.portal.reports;

import com.zonak.portal.recepcion.RadianEventRow;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class RadianEventsReportCsvExporter {
    private static final String HEADER = String.join(";",
            "Fecha",
            "Evento",
            "Código",
            "Factura",
            "CUFE",
            "Proveedor",
            "NIT",
            "Estado DIAN",
            "TrackID",
            "CUDE",
            "Notificación",
            "Correo proveedor"
    );

    private RadianEventsReportCsvExporter() {
    }

    public static byte[] export(List<RadianEventRow> rows) {
        StringBuilder content = new StringBuilder();
        content.append(HEADER).append('\n');
        for (RadianEventRow row : rows) {
            content.append(csv(row.createdAt() == null ? "" : row.createdAt().toString())).append(';')
                    .append(csv(row.eventLabel())).append(';')
                    .append(csv(row.eventCode())).append(';')
                    .append(csv(row.invoiceNumber())).append(';')
                    .append(csv(row.cufe())).append(';')
                    .append(csv(row.supplierName())).append(';')
                    .append(csv(row.supplierNit())).append(';')
                    .append(csv(row.estado())).append(';')
                    .append(csv(row.trackId())).append(';')
                    .append(csv(row.cude())).append(';')
                    .append(csv(row.notifyStatus())).append(';')
                    .append(csv(row.supplierEmail()))
                    .append('\n');
        }
        return content.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(";") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
