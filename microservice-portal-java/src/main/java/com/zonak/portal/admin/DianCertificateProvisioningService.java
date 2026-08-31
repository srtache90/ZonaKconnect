package com.zonak.portal.admin;

import com.zonak.portal.security.SensitiveDataCryptoService;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.ResourceExistsException;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

@Service
public class DianCertificateProvisioningService {
    private static final Logger log = LoggerFactory.getLogger(DianCertificateProvisioningService.class);

    private final S3Client s3Client;
    private final SecretsManagerClient secretsManagerClient;
    private final AdminPortalRepository adminPortalRepository;
    private final SensitiveDataCryptoService cryptoService;
    private final String invoiceDocumentsBucket;

    public DianCertificateProvisioningService(
            S3Client s3Client,
            SecretsManagerClient secretsManagerClient,
            AdminPortalRepository adminPortalRepository,
            SensitiveDataCryptoService cryptoService,
            @Value("${aws.s3.invoice-documents-bucket}") String invoiceDocumentsBucket
    ) {
        this.s3Client = s3Client;
        this.secretsManagerClient = secretsManagerClient;
        this.adminPortalRepository = adminPortalRepository;
        this.cryptoService = cryptoService;
        this.invoiceDocumentsBucket = invoiceDocumentsBucket;
    }

    public void provisionFromUpload(UUID sociedadId, byte[] pfxBytes, String passwordPlaintext) {
        if (sociedadId == null) {
            throw new IllegalArgumentException("sociedadId requerido");
        }
        if (pfxBytes == null || pfxBytes.length == 0) {
            throw new IllegalArgumentException("certificado .pfx vacío");
        }
        if (passwordPlaintext == null || passwordPlaintext.isBlank()) {
            throw new IllegalArgumentException("contraseña del certificado requerida");
        }
        provision(sociedadId, pfxBytes, passwordPlaintext.trim());
    }

    public void provisionFromStoredCertificate(AdminPortalRepository.CertificadoProvisionRow row) {
        if (row == null || row.sociedadId() == null) {
            return;
        }
        String certBase64 = cryptoService.decryptToString(row.contenidoBase64Enc());
        if (certBase64 == null || certBase64.isBlank()) {
            log.warn("Certificado sociedad_id={} sin contenido descifrable", row.sociedadId());
            return;
        }
        String password = cryptoService.decryptToString(row.passwordEnc());
        if (password == null || password.isBlank()) {
            log.warn("Certificado sociedad_id={} sin contraseña descifrable", row.sociedadId());
            return;
        }
        byte[] pfxBytes = Base64.getDecoder().decode(certBase64);
        provision(row.sociedadId(), pfxBytes, password.trim());
    }

    public String certificateObjectKey(UUID sociedadId) {
        return "dian/certificates/tenants/" + sociedadId + "/active.p12";
    }

    public String certificateSecretName(UUID sociedadId) {
        return "dian/certificates/tenants/" + sociedadId + "-password";
    }

    private void provision(UUID sociedadId, byte[] pfxBytes, String passwordPlaintext) {
        String objectKey = certificateObjectKey(sociedadId);
        String secretName = certificateSecretName(sociedadId);

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(invoiceDocumentsBucket)
                        .key(objectKey)
                        .contentType("application/x-pkcs12")
                        .build(),
                RequestBody.fromBytes(pfxBytes)
        );

        String secretPayload = """
                {
                  "tenant_id": "%s",
                  "certificates": {
                    "default": {
                      "p12_password": "%s",
                      "description": "Certificado DIAN por sociedad"
                    }
                  }
                }
                """.formatted(sociedadId, escapeJson(passwordPlaintext));

        upsertSecret(secretName, secretPayload);
        adminPortalRepository.syncDianCertificateKeys(sociedadId, objectKey, secretName);
        log.info("Certificado DIAN provisionado sociedad_id={} s3_key={} secret={}", sociedadId, objectKey, secretName);
    }

    private void upsertSecret(String secretName, String secretPayload) {
        try {
            secretsManagerClient.putSecretValue(
                    PutSecretValueRequest.builder()
                            .secretId(secretName)
                            .secretString(secretPayload)
                            .build()
            );
        } catch (ResourceNotFoundException ex) {
            try {
                secretsManagerClient.createSecret(
                        CreateSecretRequest.builder()
                                .name(secretName)
                                .description("Password certificado DIAN por sociedad")
                                .secretString(secretPayload)
                                .build()
                );
            } catch (ResourceExistsException ignored) {
                secretsManagerClient.putSecretValue(
                        PutSecretValueRequest.builder()
                                .secretId(secretName)
                                .secretString(secretPayload)
                                .build()
                );
            }
        }
    }

    private static String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
