package com.zonak.portal.service;

import com.zonak.portal.dto.CreateCreditNoteRequestDTO;
import com.zonak.portal.dto.CreateDebitNoteRequestDTO;
import com.zonak.portal.dto.CreateInvoiceRequestDTO;
import com.zonak.portal.dto.InvoicePdfData;
import com.zonak.portal.dto.InvoiceResponseDTO;
import com.zonak.portal.exception.InvoiceStorageException;
import com.zonak.portal.mail.InvoiceMailDispatchService;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class InvoiceOrchestratorService {
    private static final Logger log = LoggerFactory.getLogger(InvoiceOrchestratorService.class);
    private static final int APPROVAL_POLL_ATTEMPTS = 25;
    private static final Duration APPROVAL_POLL_INTERVAL = Duration.ofSeconds(1);

    private final InvoiceClientService invoiceClientService;
    private final InvoiceStorageService invoiceStorageService;
    private final InvoicePdfService invoicePdfService;
    private final InvoiceReportRepository invoiceReportRepository;
    private final InvoiceMailDispatchService invoiceMailDispatchService;

    public InvoiceOrchestratorService(
            InvoiceClientService invoiceClientService,
            InvoiceStorageService invoiceStorageService,
            InvoicePdfService invoicePdfService,
            InvoiceReportRepository invoiceReportRepository,
            InvoiceMailDispatchService invoiceMailDispatchService
    ) {
        this.invoiceClientService = invoiceClientService;
        this.invoiceStorageService = invoiceStorageService;
        this.invoicePdfService = invoicePdfService;
        this.invoiceReportRepository = invoiceReportRepository;
        this.invoiceMailDispatchService = invoiceMailDispatchService;
    }

    public Mono<UUID> processAndPersistInvoice(CreateInvoiceRequestDTO requestDTO, String tenantId) {
        return invoiceClientService.emitInvoice(requestDTO)
                .doOnNext(response -> schedulePostEmissionArtifacts(
                        response,
                        tenantId,
                        null,
                        customerEmail(requestDTO.customer())
                ))
                .map(InvoiceResponseDTO::id);
    }

    public Mono<UUID> processAndPersistCreditNote(
            CreateCreditNoteRequestDTO requestDTO,
            String tenantId,
            String emissionPointId
    ) {
        return invoiceClientService.emitCreditNote(requestDTO, tenantId, emissionPointId)
                .doOnNext(response -> schedulePostEmissionArtifacts(
                        response,
                        tenantId,
                        emissionPointId,
                        customerEmail(requestDTO.cliente())
                ))
                .map(InvoiceResponseDTO::id);
    }

    public Mono<UUID> processAndPersistDebitNote(
            CreateDebitNoteRequestDTO requestDTO,
            String tenantId,
            String emissionPointId
    ) {
        return invoiceClientService.emitDebitNote(requestDTO, tenantId, emissionPointId)
                .doOnNext(response -> schedulePostEmissionArtifacts(
                        response,
                        tenantId,
                        emissionPointId,
                        customerEmail(requestDTO.cliente())
                ))
                .map(InvoiceResponseDTO::id);
    }

    public Mono<UUID> processAndPersistInvoice(
            CreateInvoiceRequestDTO requestDTO,
            String tenantId,
            String emissionPointId
    ) {
        return processAndPersistInvoice(requestDTO, tenantId, emissionPointId, customerEmail(requestDTO.customer()));
    }

    private Mono<UUID> processAndPersistInvoice(
            CreateInvoiceRequestDTO requestDTO,
            String tenantId,
            String emissionPointId,
            String customerEmail
    ) {
        return invoiceClientService.emitInvoice(requestDTO, tenantId, emissionPointId)
                .doOnNext(response -> schedulePostEmissionArtifacts(
                        response,
                        tenantId,
                        emissionPointId,
                        customerEmail
                ))
                .map(InvoiceResponseDTO::id);
    }

    private void schedulePostEmissionArtifacts(
            InvoiceResponseDTO response,
            String tenantId,
            String emissionPointId,
            String customerEmail
    ) {
        generateAndUploadPdf(response, tenantId)
                .flatMap(pdfUrl -> invoiceMailDispatchService.dispatchToAcquirerIfConfigured(
                        tenantId,
                        response.id(),
                        emissionPointId,
                        customerEmail
                ))
                .subscribe(
                        mailResult -> {
                            if (StringUtils.hasText(mailResult)) {
                                log.info("Correo adquirente enviado invoice_id={} {}", response.id(), mailResult);
                            }
                        },
                        error -> log.warn(
                                "Post-emisión incompleta invoice_id={}: {}",
                                response.id(),
                                error.getMessage()
                        )
                );
    }

    private static String customerEmail(CreateInvoiceRequestDTO.CustomerDTO customer) {
        return customer != null && StringUtils.hasText(customer.email()) ? customer.email().trim() : null;
    }

    private Mono<String> generateAndUploadPdf(InvoiceResponseDTO response, String tenantId) {
        UUID tenantUuid = UUID.fromString(tenantId);
        return Mono.fromCallable(() -> waitForApprovedInvoice(tenantUuid, response.id()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(invoicePdfService::renderPdf)
                .flatMap(pdfBytes -> uploadPdf(tenantUuid, response.id(), pdfBytes));
    }

    public Mono<byte[]> downloadOrGeneratePdf(UUID tenantId, UUID invoiceId) {
        return Mono.fromCallable(() -> invoiceReportRepository.findPdfS3Url(tenantId, invoiceId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(existingUrl -> existingUrl
                        .map(this::downloadPdf)
                        .orElseGet(() -> generateAndStorePdf(tenantId, invoiceId)));
    }

    private Mono<byte[]> generateAndStorePdf(UUID tenantId, UUID invoiceId) {
        return Mono.fromCallable(() -> invoiceReportRepository.findApprovedInvoice(tenantId, invoiceId)
                        .or(() -> invoiceReportRepository.findInvoice(tenantId, invoiceId))
                        .orElseThrow(() -> new InvoiceStorageException("factura no encontrada para generar PDF")))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(invoicePdfService::renderPdf)
                .flatMap(pdfBytes -> uploadPdf(tenantId, invoiceId, pdfBytes).thenReturn(pdfBytes));
    }

    private Mono<byte[]> downloadPdf(String s3Url) {
        return Mono.fromCallable(() -> invoiceStorageService.downloadDocumentFromS3(s3Url))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<String> uploadPdf(UUID tenantId, UUID invoiceId, byte[] pdfBytes) {
        return Mono.fromCallable(() -> {
                    String pdfUrl = invoiceStorageService.uploadDocumentToS3(tenantId.toString(), invoiceId, pdfBytes, "pdf");
                    invoiceReportRepository.updatePdfUrl(tenantId, invoiceId, pdfUrl);
                    return pdfUrl;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private InvoicePdfData waitForApprovedInvoice(UUID tenantId, UUID invoiceId) {
        for (int attempt = 0; attempt < APPROVAL_POLL_ATTEMPTS; attempt++) {
            Optional<InvoicePdfData> invoice = invoiceReportRepository.findApprovedInvoice(tenantId, invoiceId);
            if (invoice.isPresent()) {
                return invoice.get();
            }

            try {
                Thread.sleep(APPROVAL_POLL_INTERVAL.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new InvoiceStorageException("espera interrumpida generando PDF de factura", ex);
            }
        }

        throw new InvoiceStorageException("la factura aún no está aprobada para generar PDF");
    }
}
