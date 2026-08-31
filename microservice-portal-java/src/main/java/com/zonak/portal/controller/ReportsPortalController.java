package com.zonak.portal.controller;

import com.zonak.portal.admin.PuntoVenta;
import com.zonak.portal.admin.Sociedad;
import com.zonak.portal.admin.AdminPortalRepository;
import com.zonak.portal.admin.SociedadDianContext;
import com.zonak.portal.dto.EmissionRadianReportRow;
import com.zonak.portal.dto.DocumentKindInvoiceRow;
import com.zonak.portal.dto.SalesDetailReportRow;
import com.zonak.portal.recepcion.RadianEventRepository;
import com.zonak.portal.recepcion.RadianEventRow;
import com.zonak.portal.recepcion.ReceivedInvoiceRepository;
import com.zonak.portal.recepcion.ReceivedInvoiceRow;
import com.zonak.portal.reports.EmissionRadianReportCsvExporter;
import com.zonak.portal.reports.EmissionRadianReportRepository;
import com.zonak.portal.reports.EmissionRadianSyncService;
import com.zonak.portal.reports.DocumentKindInvoiceRepository;
import com.zonak.portal.reports.DocumentKindReportCsvExporter;
import com.zonak.portal.reports.MagneticMediaExportService;
import com.zonak.portal.reports.MagneticMediaFormat;
import com.zonak.portal.reports.RadianEventsReportCsvExporter;
import com.zonak.portal.reports.ReceptionReportCsvExporter;
import com.zonak.portal.reports.SalesReportCsvExporter;
import com.zonak.portal.reports.SalesReportRepository;
import com.zonak.portal.reports.SalesReportRepository.AggregatedIncomeRow;
import com.zonak.portal.reports.SalesReportRepository.CompanyInfo;
import com.zonak.portal.service.PortalSessionService;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ReportsPortalController {
    private final PortalSessionService portalSessionService;
    private final SalesReportRepository salesReportRepository;
    private final MagneticMediaExportService magneticMediaExportService;
    private final ReceivedInvoiceRepository receivedInvoiceRepository;
    private final RadianEventRepository radianEventRepository;
    private final DocumentKindInvoiceRepository documentKindInvoiceRepository;
    private final EmissionRadianReportRepository emissionRadianReportRepository;
    private final EmissionRadianSyncService emissionRadianSyncService;
    private final AdminPortalRepository adminPortalRepository;

    public ReportsPortalController(
            PortalSessionService portalSessionService,
            SalesReportRepository salesReportRepository,
            MagneticMediaExportService magneticMediaExportService,
            ReceivedInvoiceRepository receivedInvoiceRepository,
            RadianEventRepository radianEventRepository,
            DocumentKindInvoiceRepository documentKindInvoiceRepository,
            EmissionRadianReportRepository emissionRadianReportRepository,
            EmissionRadianSyncService emissionRadianSyncService,
            AdminPortalRepository adminPortalRepository
    ) {
        this.portalSessionService = portalSessionService;
        this.salesReportRepository = salesReportRepository;
        this.magneticMediaExportService = magneticMediaExportService;
        this.receivedInvoiceRepository = receivedInvoiceRepository;
        this.radianEventRepository = radianEventRepository;
        this.documentKindInvoiceRepository = documentKindInvoiceRepository;
        this.emissionRadianReportRepository = emissionRadianReportRepository;
        this.emissionRadianSyncService = emissionRadianSyncService;
        this.adminPortalRepository = adminPortalRepository;
    }

    @GetMapping("/portal/emision/reportes")
    public String emisionReportesHub() {
        return "portal/emision/reportes/index";
    }

    @GetMapping("/portal/emision/reportes/ventas-detallado")
    public String ventasDetallado(
            @RequestParam(required = false) String sociedadId,
            @RequestParam(required = false) String emissionPointId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String estadoDian,
            HttpSession session,
            Model model
    ) {
        ReportFilters filters = resolveFilters(session, sociedadId, emissionPointId, fromDate, toDate);
        List<SalesDetailReportRow> rows = salesReportRepository.findDetailedSales(
                filters.tenantId(),
                filters.emissionPointId(),
                filters.fromDate(),
                filters.toDate(),
                estadoDian
        );

        populateEmisionFilters(model, session, filters);
        model.addAttribute("rows", rows);
        model.addAttribute("estadoDian", estadoDian);
        model.addAttribute("totalRegistros", rows.size());
        return "portal/emision/reportes/ventas-detallado";
    }

    @GetMapping("/portal/emision/reportes/ventas-detallado/export")
    public ResponseEntity<byte[]> exportVentasDetallado(
            @RequestParam(required = false) String sociedadId,
            @RequestParam(required = false) String emissionPointId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String estadoDian,
            HttpSession session
    ) {
        ReportFilters filters = resolveFilters(session, sociedadId, emissionPointId, fromDate, toDate);
        List<SalesDetailReportRow> rows = salesReportRepository.findDetailedSales(
                filters.tenantId(),
                filters.emissionPointId(),
                filters.fromDate(),
                filters.toDate(),
                estadoDian
        );

        byte[] csv = SalesReportCsvExporter.export(rows);
        String filename = "reporte-ventas-detallado-%s-%s.csv".formatted(filters.fromDate(), filters.toDate());
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(csv);
    }

    @GetMapping("/portal/emision/reportes/documentos-radian")
    public String documentosRadian(
            @RequestParam(required = false) String sociedadId,
            @RequestParam(required = false) String emissionPointId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String documentKind,
            @RequestParam(required = false) String estadoDian,
            HttpSession session,
            Model model
    ) {
        ReportFilters filters = resolveFilters(session, sociedadId, emissionPointId, fromDate, toDate);
        List<EmissionRadianReportRow> rows = emissionRadianReportRepository.findDocumentsWithRadianEvents(
                filters.tenantId(),
                filters.emissionPointId(),
                filters.fromDate(),
                filters.toDate(),
                documentKind,
                estadoDian
        );

        populateEmisionFilters(model, session, filters);
        model.addAttribute("rows", rows);
        model.addAttribute("documentKind", documentKind);
        model.addAttribute("estadoDian", estadoDian);
        model.addAttribute("totalRegistros", rows.size());
        return "portal/emision/reportes/documentos-radian";
    }

    @PostMapping("/portal/emision/reportes/documentos-radian/sync")
    public String syncDocumentosRadian(
            @RequestParam(required = false) String sociedadId,
            @RequestParam(required = false) String emissionPointId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String documentKind,
            @RequestParam(required = false) String estadoDian,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        ReportFilters filters = resolveFilters(session, sociedadId, emissionPointId, fromDate, toDate);
        SociedadDianContext context = adminPortalRepository.findSociedadDianContext(filters.tenantId());

        try {
            EmissionRadianSyncService.SyncResult result = emissionRadianSyncService.syncFromDian(
                    context,
                    filters.tenantId(),
                    filters.emissionPointId(),
                    filters.fromDate(),
                    filters.toDate(),
                    documentKind,
                    estadoDian
            );
            redirectAttributes.addFlashAttribute("syncMessage", result.summaryMessage());
            redirectAttributes.addFlashAttribute("syncSuccess", result.failedDocuments() == 0);
            if (!result.errors().isEmpty()) {
                redirectAttributes.addFlashAttribute("syncErrors", result.errors());
            }
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("syncMessage", "Error al sincronizar eventos RADIAN: " + ex.getMessage());
            redirectAttributes.addFlashAttribute("syncSuccess", false);
        }

        return "redirect:/portal/emision/reportes/documentos-radian"
                + "?sociedadId=" + filters.tenantId()
                + (filters.emissionPointId() != null ? "&emissionPointId=" + filters.emissionPointId() : "")
                + "&fromDate=" + filters.fromDate()
                + "&toDate=" + filters.toDate()
                + (documentKind != null && !documentKind.isBlank() ? "&documentKind=" + documentKind : "")
                + (estadoDian != null && !estadoDian.isBlank() ? "&estadoDian=" + estadoDian : "");
    }

    @GetMapping("/portal/emision/reportes/documentos-radian/export")
    public ResponseEntity<byte[]> exportDocumentosRadian(
            @RequestParam(required = false) String sociedadId,
            @RequestParam(required = false) String emissionPointId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String documentKind,
            @RequestParam(required = false) String estadoDian,
            HttpSession session
    ) {
        ReportFilters filters = resolveFilters(session, sociedadId, emissionPointId, fromDate, toDate);
        List<EmissionRadianReportRow> rows = emissionRadianReportRepository.findDocumentsWithRadianEvents(
                filters.tenantId(),
                filters.emissionPointId(),
                filters.fromDate(),
                filters.toDate(),
                documentKind,
                estadoDian
        );
        byte[] csv = EmissionRadianReportCsvExporter.export(rows);
        String filename = "documentos-radian-%s-%s.csv".formatted(filters.fromDate(), filters.toDate());
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(csv);
    }

    @GetMapping("/portal/emision/reportes/medios-magneticos")
    public String mediosMagneticos(
            @RequestParam(required = false) String sociedadId,
            @RequestParam(required = false) String emissionPointId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            HttpSession session,
            Model model
    ) {
        ReportFilters filters = resolveFilters(session, sociedadId, emissionPointId, fromDate, toDate);
        populateEmisionFilters(model, session, filters);
        model.addAttribute("formats", MagneticMediaFormat.values());
        model.addAttribute("selectedYear", filters.fromDate().getYear());
        model.addAttribute("selectedPeriod", filters.fromDate().getMonthValue());
        return "portal/emision/reportes/medios-magneticos";
    }

    @GetMapping("/portal/emision/reportes/medios-magneticos/export")
    public ResponseEntity<byte[]> exportMediosMagneticos(
            @RequestParam(defaultValue = "1007") String formato,
            @RequestParam(required = false) String sociedadId,
            @RequestParam(required = false) String emissionPointId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) Integer anio,
            @RequestParam(required = false) Integer periodo,
            HttpSession session
    ) {
        ReportFilters filters = resolveFilters(session, sociedadId, emissionPointId, fromDate, toDate);
        MagneticMediaFormat mediaFormat = MagneticMediaFormat.fromCode(formato);
        CompanyInfo company = salesReportRepository.findCompanyInfo(filters.tenantId());
        List<AggregatedIncomeRow> rows = salesReportRepository.aggregateIncomeByCustomer(
                filters.tenantId(),
                filters.emissionPointId(),
                filters.fromDate(),
                filters.toDate()
        );

        int year = anio != null ? anio : filters.fromDate().getYear();
        int period = periodo != null ? periodo : filters.fromDate().getMonthValue();
        byte[] content = magneticMediaExportService.export(mediaFormat, company, rows, year, period);
        String filename = "medios-magneticos-%s-%d-%02d.txt".formatted(mediaFormat.code(), year, period);

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(content);
    }

    @GetMapping("/portal/recepcion/reportes")
    public String recepcionReportes(
            @RequestParam(required = false) String sociedadId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String eventCode,
            @RequestParam(required = false) String estadoDian,
            HttpSession session,
            Model model
    ) {
        ReceptionFilters filters = resolveReceptionFilters(session, sociedadId, fromDate, toDate);
        List<RadianEventRow> rows = radianEventRepository.find(
                filters.tenantId(),
                filters.fromDate(),
                filters.toDate(),
                eventCode,
                estadoDian
        );

        List<Sociedad> sociedades = portalSessionService.resolveSociedades(session);
        model.addAttribute("sociedades", sociedades);
        model.addAttribute("selectedSociedadId", filters.tenantId().toString());
        model.addAttribute("fromDate", filters.fromDate().toString());
        model.addAttribute("toDate", filters.toDate().toString());
        model.addAttribute("eventCode", eventCode);
        model.addAttribute("estadoDian", estadoDian);
        model.addAttribute("rows", rows);
        model.addAttribute("totalRegistros", rows.size());
        model.addAttribute("navModule", "recepcion");
        model.addAttribute("navActive", "reportes");
        return "portal/recepcion/reportes";
    }

    @GetMapping("/portal/recepcion/reportes/export")
    public ResponseEntity<byte[]> exportRecepcionReportes(
            @RequestParam(required = false) String sociedadId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String eventCode,
            @RequestParam(required = false) String estadoDian,
            HttpSession session
    ) {
        ReceptionFilters filters = resolveReceptionFilters(session, sociedadId, fromDate, toDate);
        List<RadianEventRow> rows = radianEventRepository.find(
                filters.tenantId(),
                filters.fromDate(),
                filters.toDate(),
                eventCode,
                estadoDian
        );
        byte[] csv = RadianEventsReportCsvExporter.export(rows);
        String filename = "eventos-radian-%s-%s.csv".formatted(filters.fromDate(), filters.toDate());
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(csv);
    }

    @GetMapping("/portal/documento-soporte/reportes")
    public String documentoSoporteReportes(
            @RequestParam(required = false) String sociedadId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String estadoDian,
            HttpSession session,
            Model model
    ) {
        ReceptionFilters filters = resolveReceptionFilters(session, sociedadId, fromDate, toDate);
        List<DocumentKindInvoiceRow> rows = documentKindInvoiceRepository.findByKind(
                filters.tenantId(),
                "SUPPORT",
                filters.fromDate(),
                filters.toDate(),
                estadoDian
        );
        populateDocumentKindReport(model, session, filters, estadoDian, rows);
        model.addAttribute("navModule", "soporte");
        model.addAttribute("navActive", "reportes");
        model.addAttribute("partyLabel", "Proveedor");
        return "portal/documento-soporte/reportes";
    }

    @GetMapping("/portal/documento-soporte/reportes/export")
    public ResponseEntity<byte[]> exportDocumentoSoporteReportes(
            @RequestParam(required = false) String sociedadId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String estadoDian,
            HttpSession session
    ) {
        ReceptionFilters filters = resolveReceptionFilters(session, sociedadId, fromDate, toDate);
        List<DocumentKindInvoiceRow> rows = documentKindInvoiceRepository.findByKind(
                filters.tenantId(),
                "SUPPORT",
                filters.fromDate(),
                filters.toDate(),
                estadoDian
        );
        byte[] csv = DocumentKindReportCsvExporter.export(rows, "Proveedor");
        String filename = "reporte-documento-soporte-%s-%s.csv".formatted(filters.fromDate(), filters.toDate());
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(csv);
    }

    @GetMapping("/portal/nomina-electronica/reportes")
    public String nominaReportes(
            @RequestParam(required = false) String sociedadId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String estadoDian,
            HttpSession session,
            Model model
    ) {
        ReceptionFilters filters = resolveReceptionFilters(session, sociedadId, fromDate, toDate);
        List<DocumentKindInvoiceRow> rows = documentKindInvoiceRepository.findByKind(
                filters.tenantId(),
                "PAYROLL",
                filters.fromDate(),
                filters.toDate(),
                estadoDian
        );
        populateDocumentKindReport(model, session, filters, estadoDian, rows);
        model.addAttribute("navModule", "nomina");
        model.addAttribute("navActive", "reportes");
        model.addAttribute("partyLabel", "Trabajador");
        return "portal/nomina/reportes";
    }

    @GetMapping("/portal/nomina-electronica/reportes/export")
    public ResponseEntity<byte[]> exportNominaReportes(
            @RequestParam(required = false) String sociedadId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String estadoDian,
            HttpSession session
    ) {
        ReceptionFilters filters = resolveReceptionFilters(session, sociedadId, fromDate, toDate);
        List<DocumentKindInvoiceRow> rows = documentKindInvoiceRepository.findByKind(
                filters.tenantId(),
                "PAYROLL",
                filters.fromDate(),
                filters.toDate(),
                estadoDian
        );
        byte[] csv = DocumentKindReportCsvExporter.export(rows, "Trabajador");
        String filename = "reporte-nomina-%s-%s.csv".formatted(filters.fromDate(), filters.toDate());
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(csv);
    }

    private void populateDocumentKindReport(
            Model model,
            HttpSession session,
            ReceptionFilters filters,
            String estadoDian,
            List<DocumentKindInvoiceRow> rows
    ) {
        List<Sociedad> sociedades = portalSessionService.resolveSociedades(session);
        model.addAttribute("sociedades", sociedades);
        model.addAttribute("selectedSociedadId", filters.tenantId().toString());
        model.addAttribute("fromDate", filters.fromDate().toString());
        model.addAttribute("toDate", filters.toDate().toString());
        model.addAttribute("estadoDian", estadoDian);
        model.addAttribute("rows", rows);
        model.addAttribute("totalRegistros", rows.size());
    }

    @GetMapping("/portal/configuraciones/reportes")
    public String configuracionesReportes(Model model) {
        model.addAttribute("moduleName", "Configuración");
        model.addAttribute("modulePath", "/portal/configuraciones");
        model.addAttribute("moduleDescription", "Parámetros y reportes operativos de la plataforma.");
        model.addAttribute("navModule", "configuracion");
        model.addAttribute("navActive", "reportes");
        return "portal/reportes/module-reportes";
    }

    private void populateEmisionFilters(Model model, HttpSession session, ReportFilters filters) {
        List<Sociedad> sociedades = portalSessionService.resolveSociedades(session);
        List<PuntoVenta> puntosVenta = portalSessionService.resolvePuntosVenta(session);
        model.addAttribute("sociedades", sociedades);
        model.addAttribute("puntosVenta", puntosVenta);
        model.addAttribute("selectedSociedadId", filters.tenantId().toString());
        model.addAttribute("selectedEmissionPointId", filters.emissionPointId() != null ? filters.emissionPointId().toString() : "");
        model.addAttribute("fromDate", filters.fromDate().toString());
        model.addAttribute("toDate", filters.toDate().toString());
    }

    private ReportFilters resolveFilters(
            HttpSession session,
            String sociedadId,
            String emissionPointId,
            String fromDate,
            String toDate
    ) {
        List<Sociedad> sociedades = portalSessionService.resolveSociedades(session);
        String selectedSociedadId = sociedadId != null && !sociedadId.isBlank()
                ? sociedadId
                : portalSessionService.resolveSelectedSociedadId(session, sociedades);
        UUID tenantId = UUID.fromString(selectedSociedadId);
        session.setAttribute("tenantId", selectedSociedadId);

        List<PuntoVenta> puntosVenta = portalSessionService.resolvePuntosVenta(session);
        String selectedEmissionPointId = emissionPointId != null && !emissionPointId.isBlank()
                ? emissionPointId
                : portalSessionService.resolveSelectedEmissionPointId(session, puntosVenta, selectedSociedadId);

        UUID emissionPointUuid = selectedEmissionPointId.isBlank() ? null : UUID.fromString(selectedEmissionPointId);
        if (!selectedEmissionPointId.isBlank()) {
            session.setAttribute("emissionPointId", selectedEmissionPointId);
        }

        LocalDate from = parseDate(fromDate, LocalDate.now().withDayOfMonth(1));
        LocalDate to = parseDate(toDate, LocalDate.now());
        if (to.isBefore(from)) {
            to = from;
        }

        return new ReportFilters(tenantId, emissionPointUuid, from, to);
    }

    private ReceptionFilters resolveReceptionFilters(
            HttpSession session,
            String sociedadId,
            String fromDate,
            String toDate
    ) {
        List<Sociedad> sociedades = portalSessionService.resolveSociedades(session);
        String selectedSociedadId = sociedadId != null && !sociedadId.isBlank()
                ? sociedadId
                : portalSessionService.resolveSelectedSociedadId(session, sociedades);
        UUID tenantId = UUID.fromString(selectedSociedadId);
        session.setAttribute("tenantId", selectedSociedadId);

        LocalDate from = parseDate(fromDate, LocalDate.now().withDayOfMonth(1));
        LocalDate to = parseDate(toDate, LocalDate.now());
        if (to.isBefore(from)) {
            to = from;
        }
        return new ReceptionFilters(tenantId, from, to);
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return LocalDate.parse(value);
    }

    private record ReportFilters(UUID tenantId, UUID emissionPointId, LocalDate fromDate, LocalDate toDate) {
    }

    private record ReceptionFilters(UUID tenantId, LocalDate fromDate, LocalDate toDate) {
    }
}
