package com.zonak.portal.security;

/**
 * Roles de portal y helpers de capacidad.
 * ADMIN = administración de sociedad; EMISOR = emisión; RECEPTOR = recepción; CONSULTA = solo lectura.
 * OPERADOR se acepta como alias legacy de EMISOR.
 */
public final class PortalRoles {
    public static final String ADMIN = "ADMIN";
    public static final String EMISOR = "EMISOR";
    public static final String RECEPTOR = "RECEPTOR";
    public static final String CONSULTA = "CONSULTA";
    public static final String OPERADOR_LEGACY = "OPERADOR";

    private PortalRoles() {
    }

    public static String normalize(String role) {
        if (role == null || role.isBlank()) {
            return CONSULTA;
        }
        String normalized = role.trim().toUpperCase();
        if (OPERADOR_LEGACY.equals(normalized)) {
            return EMISOR;
        }
        return normalized;
    }

    public static boolean isAdmin(String role) {
        return ADMIN.equals(normalize(role));
    }

    public static boolean canEmit(String role) {
        String r = normalize(role);
        return ADMIN.equals(r) || EMISOR.equals(r);
    }

    public static boolean canReceive(String role) {
        String r = normalize(role);
        return ADMIN.equals(r) || RECEPTOR.equals(r) || CONSULTA.equals(r);
    }

    public static boolean canMutateReception(String role) {
        String r = normalize(role);
        return ADMIN.equals(r) || RECEPTOR.equals(r);
    }

    public static boolean isReadOnly(String role) {
        return CONSULTA.equals(normalize(role));
    }
}
