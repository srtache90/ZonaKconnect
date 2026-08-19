package com.zonak.portal.auth;

import java.util.UUID;

public record ApiTenant(
        UUID tenantId,
        UUID emissionPointId,
        String razonSocial
) {
}
