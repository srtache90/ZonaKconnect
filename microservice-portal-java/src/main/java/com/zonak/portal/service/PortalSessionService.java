package com.zonak.portal.service;

import com.zonak.portal.admin.AdminPortalRepository;
import com.zonak.portal.admin.PuntoVenta;
import com.zonak.portal.admin.Sociedad;
import com.zonak.portal.admin.UserAdminRepository;
import com.zonak.portal.security.PortalRoles;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PortalSessionService {
    private static final String LOCAL_TENANT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String LOCAL_EMISSION_POINT_ID = "00000000-0000-0000-0000-000000000101";

    private final AdminPortalRepository adminPortalRepository;
    private final UserAdminRepository userAdminRepository;
    private final boolean localMode;

    public PortalSessionService(
            AdminPortalRepository adminPortalRepository,
            UserAdminRepository userAdminRepository,
            @Value("${aws.local-mode:false}") boolean localMode
    ) {
        this.adminPortalRepository = adminPortalRepository;
        this.userAdminRepository = userAdminRepository;
        this.localMode = localMode;
    }

    public String resolveTenantId(HttpSession session) {
        Object tenantId = session.getAttribute("tenantId");
        if (tenantId != null) {
            return tenantId.toString();
        }

        if (localMode) {
            session.setAttribute("tenantId", LOCAL_TENANT_ID);
            session.setAttribute("emissionPointId", LOCAL_EMISSION_POINT_ID);
            return LOCAL_TENANT_ID;
        }

        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "tenantId no existe en sesión");
    }

    public UUID resolveUserId(HttpSession session) {
        Object userId = session.getAttribute("userId");
        if (userId == null || userId.toString().isBlank()) {
            return null;
        }
        return UUID.fromString(userId.toString());
    }

    public String resolveRole(HttpSession session) {
        Object role = session.getAttribute("role");
        return PortalRoles.normalize(role == null ? null : role.toString());
    }

    public List<UUID> resolveSociedadIds(HttpSession session) {
        if (PortalRoles.isAdmin(resolveRole(session))) {
            return adminPortalRepository.findSociedades().stream()
                    .map(Sociedad::id)
                    .toList();
        }

        Object tenantIds = session.getAttribute("tenantIds");
        if (tenantIds instanceof List<?> list && !list.isEmpty()) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .map(UUID::fromString)
                    .toList();
        }

        return List.of(UUID.fromString(resolveTenantId(session)));
    }

    public List<Sociedad> resolveSociedades(HttpSession session) {
        if (PortalRoles.isAdmin(resolveRole(session))) {
            return adminPortalRepository.findSociedades();
        }
        return adminPortalRepository.findSociedadesByIds(resolveSociedadIds(session));
    }

    public String resolveSelectedSociedadId(HttpSession session, List<Sociedad> sociedades) {
        Object sessionTenantId = session.getAttribute("tenantId");
        if (sessionTenantId != null) {
            String value = sessionTenantId.toString();
            boolean exists = sociedades.stream()
                    .anyMatch(sociedad -> sociedad.id().toString().equals(value));
            if (exists) {
                return value;
            }
        }

        if (sociedades.isEmpty()) {
            return "";
        }

        String firstSociedadId = sociedades.getFirst().id().toString();
        session.setAttribute("tenantId", firstSociedadId);
        return firstSociedadId;
    }

    public List<PuntoVenta> resolvePuntosVenta(HttpSession session) {
        List<UUID> sociedadIds = resolveSociedadIds(session);
        List<PuntoVenta> all = adminPortalRepository.findPuntosVentaActivosBySociedades(sociedadIds);
        UUID userId = resolveUserId(session);
        if (userId == null || PortalRoles.isAdmin(resolveRole(session))) {
            return all;
        }
        return all.stream()
                .filter(punto -> {
                    List<UUID> allowed = userAdminRepository.findAllowedEmissionPointIds(
                            userId, punto.sociedadId(), false
                    );
                    return allowed.contains(punto.id());
                })
                .toList();
    }

    public List<UUID> resolveAllowedEmissionPointIds(HttpSession session, UUID sociedadId) {
        UUID userId = resolveUserId(session);
        boolean admin = PortalRoles.isAdmin(resolveRole(session));
        if (userId == null) {
            return admin
                    ? adminPortalRepository.findPuntosVentaActivosBySociedades(List.of(sociedadId))
                    .stream().map(PuntoVenta::id).toList()
                    : List.of();
        }
        return userAdminRepository.findAllowedEmissionPointIds(userId, sociedadId, admin);
    }

    public boolean canSeeUnassignedReceived(HttpSession session, UUID sociedadId) {
        UUID userId = resolveUserId(session);
        boolean admin = PortalRoles.isAdmin(resolveRole(session));
        if (admin) {
            return true;
        }
        if (userId == null) {
            return false;
        }
        return userAdminRepository.hasUnrestrictedPoints(userId, sociedadId, false);
    }

    public String resolveSelectedEmissionPointId(
            HttpSession session,
            List<PuntoVenta> puntosVenta,
            String selectedSociedadId
    ) {
        Object sessionEmissionPointId = session.getAttribute("emissionPointId");
        if (sessionEmissionPointId != null) {
            String value = sessionEmissionPointId.toString();
            boolean exists = puntosVenta.stream()
                    .anyMatch(puntoVenta -> puntoVenta.id().toString().equals(value)
                            && puntoVenta.sociedadId().toString().equals(selectedSociedadId));
            if (exists) {
                return value;
            }
        }

        return puntosVenta.stream()
                .filter(puntoVenta -> puntoVenta.sociedadId().toString().equals(selectedSociedadId))
                .findFirst()
                .map(puntoVenta -> {
                    String value = puntoVenta.id().toString();
                    session.setAttribute("emissionPointId", value);
                    return value;
                })
                .orElse(localMode ? LOCAL_EMISSION_POINT_ID : "");
    }
}
