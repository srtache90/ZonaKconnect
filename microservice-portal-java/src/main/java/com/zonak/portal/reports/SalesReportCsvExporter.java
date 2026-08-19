package com.zonak.portal.reports;

import com.zonak.portal.dto.SalesDetailReportRow;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class SalesReportCsvExporter {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String HEADER = String.join(";",
            "Documento",
            "Fecha emisión",
            "NIT cliente",
            "Nombre cliente",
            "Dirección",
            "Email",
            "Base gravada",
            "Base exenta",
            "IVA",
            "Otros impuestos",
            "Propinas",
            "Subtotal",
            "Total",
            "Estado DIAN",
            "CUFE"
    );

    private SalesReportCsvExporter() {
    }

    public static byte[] export(List<SalesDetailReportRow> rows) {
        StringBuilder content = new StringBuilder();
        content.append(HEADER).append('\n');

        for (SalesDetailReportRow row : rows) {
            content.append(csv(row.documento())).append(';')
                    .append(csv(row.fechaEmision() != null ? row.fechaEmision().format(DATE_FORMAT) : "")).append(';')
                    .append(csv(row.nitCliente())).append(';')
                    .append(csv(row.nombreCliente())).append(';')
                    .append(csv(row.direccionCliente())).append(';')
                    .append(csv(row.emailCliente())).append(';')
                    .append(csv(row.baseGravada())).append(';')
                    .append(csv(row.baseExenta())).append(';')
                    .append(csv(row.iva())).append(';')
                    .append(csv(row.otrosImpuestos())).append(';')
                    .append(csv(row.propinas())).append(';')
                    .append(csv(row.subtotal())).append(';')
                    .append(csv(row.total())).append(';')
                    .append(csv(row.estadoDian())).append(';')
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

    private static String csv(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }
}
