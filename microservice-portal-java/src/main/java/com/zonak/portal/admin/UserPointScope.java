package com.zonak.portal.admin;

import java.util.List;
import java.util.UUID;

public record UserPointScope(
        UUID sociedadId,
        UUID emissionPointId,
        boolean allPointsOfSociety
) {
    public static UserPointScope allOf(UUID sociedadId) {
        return new UserPointScope(sociedadId, null, true);
    }

    public static UserPointScope point(UUID sociedadId, UUID emissionPointId) {
        return new UserPointScope(sociedadId, emissionPointId, false);
    }
}
