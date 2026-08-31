package com.zonak.portal.admin;

import java.util.UUID;

public record SociedadDianContext(
        UUID sociedadId,
        String nit,
        String softwareId,
        String ambiente,
        String s3CertificateKey,
        String secretsManagerPasswordKey
) {
}
