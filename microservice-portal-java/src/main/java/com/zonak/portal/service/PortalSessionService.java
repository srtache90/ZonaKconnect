package com.zonak.portal.service;

import com.zonak.portal.admin.AdminPortalRepository;
import com.zonak.portal.admin.PuntoVenta;
import com.zonak.portal.admin.Sociedad;
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
    private final boolean localMode;

    public PortalSessionService(
            AdminPortalRepository adminPortalRepository,
            @Value("${aws.local-mode:false}") boolean localMode
    ) {
        this.adminPortalRepository = adminPortalRepository;
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

    public List<UUID> resolveSociedadIds(HttpSession session) {
        if ("ADMIN".equals(session.getAttribute("role"))) {
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
        if ("ADMIN".equals(session.getAttribute("role"))) {
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
        return adminPortalRepository.findPuntosVentaActivosBySociedades(sociedadIds);
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
