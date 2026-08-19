package com.zonak.portal.reports;

import com.zonak.portal.recepcion.ReceivedInvoiceRow;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ReceptionReportCsvExporter {
    private static final String HEADER = String.join(";",
            "Proveedor",
            "NIT",
            "Factura",
            "CUFE",
            "Total",
            "Fecha",
            "Estado DIAN"
    );

    private ReceptionReportCsvExporter() {
    }

    public static byte[] export(List<ReceivedInvoiceRow> rows) {
        StringBuilder content = new StringBuilder();
        content.append(HEADER).append('\n');
        for (ReceivedInvoiceRow row : rows) {
            content.append(csv(row.proveedorName())).append(';')
                    .append(csv(row.proveedorNit())).append(';')
                    .append(csv(row.invoiceNumber())).append(';')
                    .append(csv(row.cufe())).append(';')
                    .append(row.totalAmount() == null ? "0" : row.totalAmount().toPlainString()).append(';')
                    .append(csv(row.fechaEmision())).append(';')
                    .append(csv(row.estadoDian() == null ? "" : row.estadoDian().name()))
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
