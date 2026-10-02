package com.zonak.portal.dashboard;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xddf.usermodel.XDDFColor;
import org.apache.poi.xddf.usermodel.XDDFShapeProperties;
import org.apache.poi.xddf.usermodel.XDDFSolidFillProperties;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.AxisCrosses;
import org.apache.poi.xddf.usermodel.chart.BarDirection;
import org.apache.poi.xddf.usermodel.chart.BarGrouping;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.LegendPosition;
import org.apache.poi.xddf.usermodel.chart.XDDFBarChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFCategoryAxis;
import org.apache.poi.xddf.usermodel.chart.XDDFChart;
import org.apache.poi.xddf.usermodel.chart.XDDFChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFChartLegend;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFValueAxis;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.DefaultIndexedColorMap;
import org.springframework.stereotype.Service;

@Service
public class PortalDashboardExportService {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final List<Kpi> KPIS = List.of(
            new Kpi("Documentos emitidos hoy", "emitted_today"),
            new Kpi("Documentos emitidos este mes", "emitted_month"),
            new Kpi("Aceptados por DIAN", "accepted_dian"),
            new Kpi("Rechazados por DIAN", "rejected_dian"),
            new Kpi("Pendientes de recepción", "pending_reception"),
            new Kpi("Documentos soporte", "support_documents"),
            new Kpi("Documentos de nómina", "payroll_documents")
    );

    public byte[] toPdf(
            int year,
            Map<String, Object> kpis,
            List<PortalAnalyticsRepository.MonthlyEmission> monthlyEmissions,
            List<PortalAnalyticsRepository.RecentActivity> activities,
            boolean monthlyEmissionsAvailable
    ) {
        String html = buildPdfHtml(year, kpis, monthlyEmissions, activities, monthlyEmissionsAvailable);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            new PdfRendererBuilder()
                    .withHtmlContent(html, null)
                    .toStream(output)
                    .run();
            return output.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible generar el reporte PDF del dashboard", ex);
        }
    }

    public byte[] toExcel(
            int year,
            Map<String, Object> kpis,
            List<PortalAnalyticsRepository.MonthlyEmission> monthlyEmissions,
            List<PortalAnalyticsRepository.RecentActivity> activities
    ) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle titleStyle = workbook.createCellStyle();
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 16);
            titleStyle.setFont(titleFont);

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            XSSFSheet summary = (XSSFSheet) workbook.createSheet("Resumen");
                CellStyle brandBandStyle = workbook.createCellStyle();
                ((org.apache.poi.xssf.usermodel.XSSFCellStyle) brandBandStyle).setFillForegroundColor(
                    new XSSFColor(new byte[]{0x28, 0x26, 0x22}, new DefaultIndexedColorMap())
                );
            brandBandStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            for (int rowIndex = 0; rowIndex < 3; rowIndex++) {
                Row row = summary.createRow(rowIndex);
                for (int column = 0; column < 3; column++) {
                    row.createCell(column).setCellStyle(brandBandStyle);
                }
            }

            byte[] logoBytes = loadLogo();
            int logoPicture = workbook.addPicture(logoBytes, Workbook.PICTURE_TYPE_PNG);
            XSSFDrawing drawing = summary.createDrawingPatriarch();
            XSSFClientAnchor logoAnchor = drawing.createAnchor(0, 0, 0, 0, 0, 0, 3, 3);
            drawing.createPicture(logoAnchor, logoPicture);

            Row title = summary.createRow(3);
            title.createCell(0).setCellValue("Reporte Zona K - " + year);
            title.getCell(0).setCellStyle(titleStyle);

            Row kpiHeader = summary.createRow(5);
            setCell(kpiHeader, 0, "Indicador", headerStyle);
            setCell(kpiHeader, 1, "Valor", headerStyle);
            int rowIndex = 6;
            for (Kpi kpi : KPIS) {
                Row row = summary.createRow(rowIndex++);
                row.createCell(0).setCellValue(kpi.label());
                row.createCell(1).setCellValue(numberValue(kpis.get(kpi.key())));
            }

            int monthHeaderIndex = 15;
            Row monthHeader = summary.createRow(monthHeaderIndex);
            setCell(monthHeader, 0, "Mes", headerStyle);
            setCell(monthHeader, 1, "Documentos emitidos", headerStyle);
            int firstMonthRow = monthHeaderIndex + 1;
            for (int index = 0; index < monthlyEmissions.size(); index++) {
                PortalAnalyticsRepository.MonthlyEmission emission = monthlyEmissions.get(index);
                Row row = summary.createRow(firstMonthRow + index);
                row.createCell(0).setCellValue(emission.month());
                row.createCell(1).setCellValue(emission.total());
            }

            if (!monthlyEmissions.isEmpty()) {
                XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, 3, 5, 11, 19);
                XSSFChart chart = drawing.createChart(anchor);
                chart.setTitleText("Documentos emitidos por mes");
                chart.setTitleOverlay(false);
                XDDFCategoryAxis categoryAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
                XDDFValueAxis valueAxis = chart.createValueAxis(AxisPosition.LEFT);
                valueAxis.setCrosses(AxisCrosses.AUTO_ZERO);
                XDDFChartData chartData = chart.createData(ChartTypes.BAR, categoryAxis, valueAxis);
                XDDFBarChartData barData = (XDDFBarChartData) chartData;
                barData.setBarDirection(BarDirection.COL);
                barData.setBarGrouping(BarGrouping.CLUSTERED);
                var categories = XDDFDataSourcesFactory.fromStringCellRange(
                        summary, new CellRangeAddress(firstMonthRow, firstMonthRow + monthlyEmissions.size() - 1, 0, 0)
                );
                XDDFNumericalDataSource<Double> values = XDDFDataSourcesFactory.fromNumericCellRange(
                        summary, new CellRangeAddress(firstMonthRow, firstMonthRow + monthlyEmissions.size() - 1, 1, 1)
                );
                XDDFChartData.Series series = chartData.addSeries(categories, values);
                series.setTitle("Documentos", null);
                XDDFShapeProperties seriesStyle = new XDDFShapeProperties();
                seriesStyle.setFillProperties(new XDDFSolidFillProperties(
                    XDDFColor.from(new byte[]{(byte) 0x78, (byte) 0x91, (byte) 0x78})
                ));
                series.setShapeProperties(seriesStyle);
                chart.plot(chartData);
            } else {
                summary.createRow(14).createCell(0).setCellValue("No se pudieron cargar los datos mensuales.");
            }

            summary.setColumnWidth(0, 34 * 256);
            summary.setColumnWidth(1, 22 * 256);

            Sheet activitySheet = workbook.createSheet("Actividad reciente");
            Row activityHeader = activitySheet.createRow(0);
            setCell(activityHeader, 0, "Factura", headerStyle);
            setCell(activityHeader, 1, "Estado DIAN", headerStyle);
            setCell(activityHeader, 2, "Fecha de creación", headerStyle);
            setCell(activityHeader, 3, "Última actualización", headerStyle);
            for (int index = 0; index < activities.size(); index++) {
                PortalAnalyticsRepository.RecentActivity activity = activities.get(index);
                Row row = activitySheet.createRow(index + 1);
                row.createCell(0).setCellValue(activity.documentNumber());
                row.createCell(1).setCellValue(activity.estadoDian());
                row.createCell(2).setCellValue(formatDate(activity.createdAt()));
                row.createCell(3).setCellValue(formatDate(activity.updatedAt()));
            }
            for (int column = 0; column < 4; column++) {
                activitySheet.autoSizeColumn(column);
            }

            workbook.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("No fue posible generar el reporte Excel del dashboard", ex);
        }
    }

    private String buildPdfHtml(
            int year,
            Map<String, Object> kpis,
            List<PortalAnalyticsRepository.MonthlyEmission> monthlyEmissions,
            List<PortalAnalyticsRepository.RecentActivity> activities,
            boolean monthlyEmissionsAvailable
    ) {
        long maximum = monthlyEmissions.stream().mapToLong(PortalAnalyticsRepository.MonthlyEmission::total).max().orElse(0);
        String logoDataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(loadLogo());
        StringBuilder html = new StringBuilder("""
            <html><head><meta charset="UTF-8"/><style>
                body{font-family:sans-serif;color:#242424;font-size:11px;margin:28px}
                h1{font-size:20px;margin:0 0 4px}h2{font-size:14px;margin:24px 0 8px}
                table{width:100%;border-collapse:collapse;margin:8px 0 18px}
                th,td{border:1px solid #d9d9d9;padding:6px;text-align:left}
                th{background:#f1f1ed}.chart td{height:130px;text-align:center;vertical-align:bottom;padding:4px 2px}
                .bar{width:14px;margin:0 auto;background:#55a84f}
                .month{font-size:9px;color:#444}.value{font-size:9px;color:#444}
            .brand{background:#282622;color:#fff;margin-bottom:20px}.brand td{border:0;background:#282622;color:#fff;padding:12px}
                </style></head><body>
                """);
        html.append("<table class=\"brand\"><tr><td style=\"width:210px\"><img src=\"")
            .append(logoDataUri).append("\" alt=\"Zona K\" style=\"width:180px;height:auto\"/></td>")
            .append("<td><h1>Reporte de documentos</h1><div>Resumen anual ").append(year)
            .append("</div></td></tr></table>");
        html.append("<h2>Indicadores</h2><table><tr><th>Indicador</th><th>Valor</th></tr>");
        for (Kpi kpi : KPIS) {
            html.append("<tr><td>").append(escapeHtml(kpi.label())).append("</td><td>")
                    .append(numberValue(kpis.get(kpi.key()))).append("</td></tr>");
        }
        html.append("</table><h2>Documentos emitidos por mes</h2>");
        if (!monthlyEmissionsAvailable) {
            html.append("<p>No se pudieron cargar los datos mensuales.</p>");
        } else if (maximum == 0) {
            html.append("<p>Sin documentos emitidos en ").append(year)
                    .append(". Esto no es un error si aún no hay facturas este año.</p>");
        }
        html.append("<table class=\"chart\"><tr>");
        for (PortalAnalyticsRepository.MonthlyEmission emission : monthlyEmissions) {
            int height = maximum == 0 ? 0 : (int) (emission.total() * 92 / maximum);
            html.append("<td><div class=\"bar\" style=\"height:")
                .append(height).append("px\"></div><div class=\"month\">")
                    .append(escapeHtml(emission.month())).append("</div><div class=\"value\">")
                    .append(emission.total()).append("</div></td>");
        }
        html.append("</tr></table><h2>Actividad reciente</h2><table><tr><th>Factura</th><th>Estado DIAN</th>")
                .append("<th>Creación</th><th>Última actualización</th></tr>");
        for (PortalAnalyticsRepository.RecentActivity activity : activities) {
            html.append("<tr><td>").append(escapeHtml(activity.documentNumber())).append("</td><td>")
                    .append(escapeHtml(activity.estadoDian())).append("</td><td>")
                    .append(escapeHtml(formatDate(activity.createdAt()))).append("</td><td>")
                    .append(escapeHtml(formatDate(activity.updatedAt()))).append("</td></tr>");
        }
        return html.append("</table></body></html>").toString();
    }

    private static void setCell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static double numberValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private byte[] loadLogo() {
        try (InputStream logo = getClass().getResourceAsStream("/static/imagen/Zonak1.png")) {
            if (logo == null) {
                throw new IllegalStateException("No se encontró el logo de Zona K");
            }
            return logo.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("No fue posible leer el logo de Zona K", ex);
        }
    }

    private static String formatDate(OffsetDateTime value) {
        return value == null ? "" : value.format(DATE_FORMAT);
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private record Kpi(String label, String key) {
    }
}