package com.zonak.portal.reports;

import com.zonak.portal.dto.DocumentKindInvoiceRow;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class DocumentKindReportCsvExporter {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private DocumentKindReportCsvExporter() {
    }

    public static byte[] export(List<DocumentKindInvoiceRow> rows, String partyLabel) {
        StringBuilder content = new StringBuilder();
        content.append(String.join(";",
                "Documento",
                partyLabel,
                "Identificación",
                "Total",
                "Estado DIAN",
                "Fecha",
                "CUFE/CUNE"
        )).append('\n');

        for (DocumentKindInvoiceRow row : rows) {
            content.append(csv(row.documento())).append(';')
                    .append(csv(row.partyName())).append(';')
                    .append(csv(row.partyId())).append(';')
                    .append(row.total() == null ? "0" : row.total().toPlainString()).append(';')
                    .append(csv(row.estadoDian())).append(';')
                    .append(csv(row.createdAt() == null ? "" : DATE_TIME.format(row.createdAt()))).append(';')
                    .append(csv(row.cufe()))
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
