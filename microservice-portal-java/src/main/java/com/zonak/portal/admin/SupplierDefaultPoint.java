package com.zonak.portal.admin;

import java.util.UUID;

public record SupplierDefaultPoint(
        UUID id,
        UUID companyId,
        String supplierNit,
        UUID emissionPointId,
        String emissionPointLabel,
        String notes,
        boolean active
) {
}
