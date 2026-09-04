package com.zonak.portal.admin;

import com.zonak.portal.security.SensitiveDataCryptoService;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.DescribeSecretRequest;
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

    public SociedadDianContext ensureAvailable(SociedadDianContext context) {
        if (context == null || context.sociedadId() == null) {
            throw new IllegalArgumentException("Contexto DIAN de la sociedad requerido");
        }

        UUID sociedadId = context.sociedadId();
        String objectKey = StringUtils.hasText(context.s3CertificateKey())
                ? context.s3CertificateKey().trim()
                : certificateObjectKey(sociedadId);
        String secretName = StringUtils.hasText(context.secretsManagerPasswordKey())
                ? context.secretsManagerPasswordKey().trim()
                : certificateSecretName(sociedadId);

        if (objectExists(objectKey) && secretExists(secretName)) {
            if (!StringUtils.hasText(context.s3CertificateKey())
                    || !StringUtils.hasText(context.secretsManagerPasswordKey())) {
                adminPortalRepository.syncDianCertificateKeys(sociedadId, objectKey, secretName);
                return reloadContext(sociedadId);
            }
            return context;
        }

        log.warn(
                "Certificado DIAN ausente en S3/Secrets para sociedad_id={} key={} secret={}. Re-publicando desde BD.",
                sociedadId,
                objectKey,
                secretName
        );
        republishFromDatabase(sociedadId);
        return reloadContext(sociedadId);
    }

    public void ensureStoredCertificatePublished(AdminPortalRepository.CertificadoProvisionRow row) {
        if (row == null || row.sociedadId() == null) {
            return;
        }
        String objectKey = certificateObjectKey(row.sociedadId());
        String secretName = certificateSecretName(row.sociedadId());
        if (objectExists(objectKey) && secretExists(secretName)) {
            adminPortalRepository.syncDianCertificateKeys(row.sociedadId(), objectKey, secretName);
            return;
        }
        provisionFromStoredCertificate(row);
    }

    public void provisionFromStoredCertificate(AdminPortalRepository.CertificadoProvisionRow row) {
        if (row == null || row.sociedadId() == null) {
            throw new IllegalArgumentException("Certificado de sociedad requerido");
        }
        String certBase64 = cryptoService.decryptToString(row.contenidoBase64Enc());
        if (certBase64 == null || certBase64.isBlank()) {
            throw new IllegalStateException(
                    "Certificado sociedad_id=" + row.sociedadId() + " sin contenido descifrable"
            );
        }
        String password = cryptoService.decryptToString(row.passwordEnc());
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Certificado sociedad_id=" + row.sociedadId() + " sin contraseña descifrable"
            );
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

    private void republishFromDatabase(UUID sociedadId) {
        AdminPortalRepository.CertificadoProvisionRow row =
                adminPortalRepository.findActiveCertificadoForProvision(sociedadId);
        if (row == null) {
            throw new IllegalStateException(
                    "El certificado digital no está en S3 y la sociedad no tiene un .p12 activo. "
                            + "Cargue el certificado en Configuración > Certificados e intente de nuevo."
            );
        }
        provisionFromStoredCertificate(row);
    }

    private SociedadDianContext reloadContext(UUID sociedadId) {
        SociedadDianContext updated = adminPortalRepository.findSociedadDianContext(sociedadId);
        if (updated == null) {
            throw new IllegalStateException("Sociedad no encontrada tras publicar el certificado DIAN");
        }
        return updated;
    }

    private boolean objectExists(String objectKey) {
        try {
            s3Client.headObject(
                    HeadObjectRequest.builder()
                            .bucket(invoiceDocumentsBucket)
                            .key(objectKey)
                            .build()
            );
            return true;
        } catch (NoSuchKeyException ex) {
            return false;
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return false;
            }
            throw ex;
        }
    }

    private boolean secretExists(String secretName) {
        try {
            secretsManagerClient.describeSecret(
                    DescribeSecretRequest.builder()
                            .secretId(secretName)
                            .build()
            );
            return true;
        } catch (ResourceNotFoundException ex) {
            return false;
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
