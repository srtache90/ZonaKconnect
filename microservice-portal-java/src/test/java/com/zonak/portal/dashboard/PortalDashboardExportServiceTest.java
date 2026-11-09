package com.zonak.portal.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class PortalDashboardExportServiceTest {
    private final PortalDashboardExportService exportService = new PortalDashboardExportService();

    @Test
    void exportsPdfAndExcelWithMonthlyChartAndActivity() throws Exception {
        Map<String, Object> kpis = Map.of("emitted_month", 3L, "emitted_today", 1L);
        List<PortalAnalyticsRepository.MonthlyEmission> monthlyEmissions = List.of(
                new PortalAnalyticsRepository.MonthlyEmission("Ene", 2),
                new PortalAnalyticsRepository.MonthlyEmission("Feb", 3)
        );
        List<PortalAnalyticsRepository.RecentActivity> activities = List.of(
                new PortalAnalyticsRepository.RecentActivity("ZK-1", "ENVIADO", null, null)
        );

        byte[] pdf = exportService.toPdf(2026, kpis, monthlyEmissions, activities, true);
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");

        byte[] excel = exportService.toExcel(2026, kpis, monthlyEmissions, activities);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excel))) {
            assertThat(workbook.getSheet("Resumen")).isNotNull();
            assertThat(workbook.getSheet("Actividad reciente")).isNotNull();
            assertThat(workbook.getAllPictures()).hasSize(1);
            assertThat(workbook.getSheet("Resumen").getDrawingPatriarch().getCharts()).hasSize(1);
                assertThat(workbook.getSheet("Resumen").getRow(0).getCell(0).getCellStyle()
                    .getFillForegroundColorColor().getRGB()).containsExactly((byte) 0x28, (byte) 0x26, (byte) 0x22);
        }
    }
}