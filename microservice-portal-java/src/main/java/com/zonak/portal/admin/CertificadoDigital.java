package com.zonak.portal.admin;

import java.time.LocalDate;
import java.util.UUID;

public record CertificadoDigital(
        UUID id,
        UUID sociedadId,
        String sociedadRazonSocial,
        String alias,
        LocalDate validoHasta,
        boolean activo
) {
}
