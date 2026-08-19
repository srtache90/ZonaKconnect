package com.zonak.portal.auth;

import java.util.List;
import java.util.UUID;

public record AuthenticatedUser(
        UUID id,
        String username,
        String passwordHash,
        String role,
        List<UUID> tenantIds
) {
}
