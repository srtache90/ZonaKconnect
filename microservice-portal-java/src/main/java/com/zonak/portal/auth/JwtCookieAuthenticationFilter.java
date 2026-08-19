package com.zonak.portal.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtCookieAuthenticationFilter extends OncePerRequestFilter {
    private static final String LOCAL_TENANT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String LOCAL_EMISSION_POINT_ID = "00000000-0000-0000-0000-000000000101";

    private final JwtCookieService jwtCookieService;

    public JwtCookieAuthenticationFilter(JwtCookieService jwtCookieService) {
        this.jwtCookieService = jwtCookieService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String token = jwtCookieService.readToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticateFromToken(request, token);
        }

        filterChain.doFilter(request, response);
    }

    private void authenticateFromToken(HttpServletRequest request, String token) {
        try {
            Map<String, Object> claims = jwtCookieService.verifyToken(token);
            String username = claims.get("sub").toString();
            String role = claims.get("role").toString();
            List<String> tenantIds = jwtCookieService.tenantIds(claims);

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    username,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role))
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);

            HttpSession session = request.getSession(true);
            session.setAttribute("role", role);
            session.setAttribute("username", username);
            if (!tenantIds.isEmpty()) {
                session.setAttribute("tenantIds", tenantIds);
                Object existingTenantId = session.getAttribute("tenantId");
                boolean existingValid = existingTenantId != null && tenantIds.contains(existingTenantId.toString());
                if (!existingValid) {
                    String tenantId = tenantIds.contains(LOCAL_TENANT_ID)
                            ? LOCAL_TENANT_ID
                            : tenantIds.getFirst();
                    session.setAttribute("tenantId", tenantId);
                }
            }
            Object existingEmissionPointId = session.getAttribute("emissionPointId");
            if (existingEmissionPointId == null || existingEmissionPointId.toString().isBlank()) {
                session.setAttribute("emissionPointId", LOCAL_EMISSION_POINT_ID);
            }
        } catch (IllegalArgumentException ignored) {
            SecurityContextHolder.clearContext();
        }
    }
}
