package com.zonak.portal.reports;

import com.zonak.portal.dto.EmissionRadianReportRow;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class EmissionRadianReportCsvExporter {
    private static final String HEADER = String.join(";",
            "Tipo documento",
            "Factura",
            "Nota crédito",
            "Nota débito",
            "Documento",
            "Fecha emisión",
            "CUFE/CUDE",
            "Valor total",
            "Estado DIAN",
            "Eventos RADIAN"
    );

    private EmissionRadianReportCsvExporter() {
    }

    public static byte[] export(List<EmissionRadianReportRow> rows) {
        StringBuilder content = new StringBuilder();
        content.append(HEADER).append('\n');
        for (EmissionRadianReportRow row : rows) {
            content.append(csv(row.documentKindLabel())).append(';')
                    .append(csv(row.facturaNumero())).append(';')
                    .append(csv(row.notaCreditoNumero())).append(';')
                    .append(csv(row.notaDebitoNumero())).append(';')
                    .append(csv(row.documentoNumero())).append(';')
                    .append(csv(row.fechaEmision() == null ? "" : row.fechaEmision().toString())).append(';')
                    .append(csv(row.cufe())).append(';')
                    .append(csv(row.valorTotal() == null ? "" : row.valorTotal().toPlainString())).append(';')
                    .append(csv(row.estadoDian())).append(';')
                    .append(csv(row.radianEventsText()))
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
