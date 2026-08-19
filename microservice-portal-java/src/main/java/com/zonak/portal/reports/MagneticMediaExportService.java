package com.zonak.portal.reports;

import com.zonak.portal.reports.SalesReportRepository.AggregatedIncomeRow;
import com.zonak.portal.reports.SalesReportRepository.CompanyInfo;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MagneticMediaExportService {

    public byte[] exportFormato1007(
            CompanyInfo company,
            List<AggregatedIncomeRow> rows,
            int year,
            int period
    ) {
        StringBuilder content = new StringBuilder();
        content.append(buildHeader(company, "1007", year, period, rows.size())).append('\n');

        for (AggregatedIncomeRow row : rows) {
            String[] nitParts = splitNit(row.nit());
            content.append(String.join("|",
                    "13",
                    nitParts[0],
                    nitParts[1],
                    "",
                    "",
                    "",
                    "",
                    sanitize(row.razonSocial()),
                    sanitize(row.direccion()),
                    "",
                    "",
                    "169",
                    plain(row.ingresosGravados().add(row.ingresosExentos())),
                    "0",
                    plain(row.iva()),
                    "0",
                    "0",
                    "0",
                    "0",
                    "0",
                    "0",
                    "0",
                    "0"
            )).append('\n');
        }

        return withBom(content.toString());
    }

    public byte[] exportFormato1001(
            CompanyInfo company,
            List<AggregatedIncomeRow> rows,
            int year,
            int period
    ) {
        StringBuilder content = new StringBuilder();
        content.append(buildHeader(company, "1001", year, period, rows.size())).append('\n');

        for (AggregatedIncomeRow row : rows) {
            String[] nitParts = splitNit(row.nit());
            content.append(String.join("|",
                    "13",
                    nitParts[0],
                    nitParts[1],
                    "",
                    "",
                    "",
                    "",
                    sanitize(row.razonSocial()),
                    sanitize(row.direccion()),
                    "",
                    "",
                    "169",
                    plain(row.totalIngresos()),
                    "0",
                    "0",
                    "0",
                    "0",
                    "0",
                    "0",
                    "0",
                    "0",
                    "0",
                    "0",
                    "0",
                    "0",
                    "0",
                    "0",
                    "0",
                    "0"
            )).append('\n');
        }

        return withBom(content.toString());
    }

    public byte[] export(
            MagneticMediaFormat format,
            CompanyInfo company,
            List<AggregatedIncomeRow> rows,
            int year,
            int period
    ) {
        return switch (format) {
            case FORMATO_1007 -> exportFormato1007(company, rows, year, period);
            case FORMATO_1001 -> exportFormato1001(company, rows, year, period);
        };
    }

    private String buildHeader(CompanyInfo company, String formatCode, int year, int period, int recordCount) {
        return String.join("|",
                "1",
                formatCode,
                String.valueOf(year),
                String.valueOf(period),
                "01",
                sanitize(company.nit()),
                sanitize(company.dv()),
                sanitize(company.razonSocial()),
                String.valueOf(recordCount),
                LocalDate.now().toString()
        );
    }

    private String[] splitNit(String nit) {
        if (nit == null || nit.isBlank()) {
            return new String[]{"", ""};
        }
        String normalized = nit.replaceAll("[^0-9]", "");
        if (normalized.length() <= 1) {
            return new String[]{normalized, ""};
        }
        return new String[]{
                normalized.substring(0, normalized.length() - 1),
                normalized.substring(normalized.length() - 1)
        };
    }

    private String plain(BigDecimal value) {
        return value == null ? "0" : value.setScale(0, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", " ").replace("\n", " ").trim();
    }

    private byte[] withBom(String content) {
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, result, 0, bom.length);
        System.arraycopy(body, 0, result, bom.length, body.length);
        return result;
    }
}
