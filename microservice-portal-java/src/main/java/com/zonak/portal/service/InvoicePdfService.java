package com.zonak.portal.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.zonak.portal.dto.InvoicePdfData;
import com.zonak.portal.exception.InvoiceStorageException;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class InvoicePdfService {
    private final TemplateEngine templateEngine;
    private final QrCodeService qrCodeService;

    public InvoicePdfService(TemplateEngine templateEngine, QrCodeService qrCodeService) {
        this.templateEngine = templateEngine;
        this.qrCodeService = qrCodeService;
    }

    public Mono<byte[]> renderPdf(InvoicePdfData invoice) {
        return Mono.fromCallable(() -> renderBlocking(invoice))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private byte[] renderBlocking(InvoicePdfData invoice) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Context context = new Context(Locale.forLanguageTag("es-CO"));
            context.setVariable("invoice", invoice);
            context.setVariable("qrDataUri", qrCodeService.toDataUri(invoice.fiscalContext().qrUrl()));
            context.setVariable("valorEnLetras", valorEnLetras(invoice.totals().total()));

            String html = templateEngine.process(invoice.fiscalContext().templateName(), context);
            new PdfRendererBuilder()
                    .withHtmlContent(html, null)
                    .toStream(output)
                    .run();
            return output.toByteArray();
        } catch (Exception ex) {
            throw new InvoiceStorageException("no fue posible generar la representación gráfica PDF", ex);
        }
    }

    private String valorEnLetras(BigDecimal value) {
        BigInteger pesos = value.setScale(0, java.math.RoundingMode.HALF_UP).toBigInteger();
        return toWords(pesos.longValueExact()).toUpperCase(Locale.ROOT) + " PESOS M/CTE";
    }

    private String toWords(long value) {
        if (value == 0) {
            return "cero";
        }
        if (value < 0) {
            return "menos " + toWords(Math.abs(value));
        }

        return join(List.of(
                group(value / 1_000_000_000, "mil millones", "mil millones"),
                group((value / 1_000_000) % 1000, "millón", "millones"),
                group((value / 1000) % 1000, "mil", "mil"),
                underThousand(value % 1000)
        ));
    }

    private String group(long value, String singular, String plural) {
        if (value == 0) {
            return "";
        }
        if (value == 1 && singular.equals("mil")) {
            return "mil";
        }
        if (value == 1) {
            return "un " + singular;
        }
        return underThousand(value) + " " + plural;
    }

    private String underThousand(long value) {
        String[] units = {
                "", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve",
                "diez", "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete",
                "dieciocho", "diecinueve", "veinte", "veintiuno", "veintidós", "veintitrés",
                "veinticuatro", "veinticinco", "veintiséis", "veintisiete", "veintiocho", "veintinueve"
        };
        String[] tens = {"", "", "veinte", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa"};
        String[] hundreds = {"", "ciento", "doscientos", "trescientos", "cuatrocientos", "quinientos", "seiscientos", "setecientos", "ochocientos", "novecientos"};

        if (value < 30) {
            return units[(int) value];
        }
        if (value < 100) {
            long ten = value / 10;
            long unit = value % 10;
            return unit == 0 ? tens[(int) ten] : tens[(int) ten] + " y " + units[(int) unit];
        }
        if (value == 100) {
            return "cien";
        }

        long hundred = value / 100;
        long rest = value % 100;
        return rest == 0 ? hundreds[(int) hundred] : hundreds[(int) hundred] + " " + underThousand(rest);
    }

    private String join(List<String> parts) {
        return parts.stream()
                .filter(part -> part != null && !part.isBlank())
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }
}
