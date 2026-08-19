package com.zonak.portal.service;

import com.zonak.portal.exception.InvoiceStorageException;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Service
public class InvoiceStorageService {
    private final S3Client s3Client;
    private final String invoiceDocumentsBucket;

    public InvoiceStorageService(
            S3Client s3Client,
            @Value("${aws.s3.invoice-documents-bucket}") String invoiceDocumentsBucket
    ) {
        this.s3Client = s3Client;
        this.invoiceDocumentsBucket = invoiceDocumentsBucket;
    }

    public String uploadDocumentToS3(String tenantId, UUID invoiceId, byte[] content, String fileExtension) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new InvoiceStorageException("tenantId requerido para almacenar documento");
        }
        if (invoiceId == null) {
            throw new InvoiceStorageException("invoiceId requerido para almacenar documento");
        }
        if (content == null || content.length == 0) {
            throw new InvoiceStorageException("contenido vacío para invoice_id=" + invoiceId);
        }

        String extension = normalizeExtension(fileExtension);
        String key = "tenants/%s/invoices/%d/%s.%s".formatted(
                tenantId,
                Year.now(ZoneOffset.UTC).getValue(),
                invoiceId,
                extension
        );

        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(invoiceDocumentsBucket)
                            .key(key)
                            .contentType(contentType(extension))
                            .build(),
                    RequestBody.fromBytes(content)
            );
        } catch (S3Exception ex) {
            throw new InvoiceStorageException("falló carga S3 invoice_id=" + invoiceId + " key=" + key, ex);
        }

        return "s3://" + invoiceDocumentsBucket + "/" + key;
    }

    public byte[] downloadDocumentFromS3(String s3Url) {
        S3Location location = parseS3Location(s3Url);
        try {
            ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(location.bucket())
                            .key(location.key())
                            .build()
            );
            return response.asByteArray();
        } catch (S3Exception ex) {
            throw new InvoiceStorageException("falló descarga S3 key=" + location.key(), ex);
        }
    }

    private String normalizeExtension(String fileExtension) {
        if (fileExtension == null || fileExtension.isBlank()) {
            throw new InvoiceStorageException("extensión de archivo requerida");
        }

        String extension = fileExtension
                .replace(".", "")
                .toLowerCase(Locale.ROOT)
                .trim();

        if (!extension.equals("pdf") && !extension.equals("xml")) {
            throw new InvoiceStorageException("extensión no soportada: " + fileExtension);
        }

        return extension;
    }

    private String contentType(String extension) {
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "xml" -> "application/xml";
            default -> "application/octet-stream";
        };
    }

    private S3Location parseS3Location(String s3Url) {
        if (s3Url == null || !s3Url.startsWith("s3://")) {
            throw new InvoiceStorageException("URL S3 inválida para documento: " + s3Url);
        }

        String withoutScheme = s3Url.substring("s3://".length());
        int separator = withoutScheme.indexOf('/');
        if (separator <= 0 || separator == withoutScheme.length() - 1) {
            throw new InvoiceStorageException("URL S3 incompleta para documento: " + s3Url);
        }
        return new S3Location(
                withoutScheme.substring(0, separator),
                withoutScheme.substring(separator + 1)
        );
    }

    private record S3Location(String bucket, String key) {
    }
}
