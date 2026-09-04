package com.zonak.portal.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.GenericFilterBean;

@Component
public class ApiKeyAuthenticationFilter extends GenericFilterBean {
    public static final String API_TENANT_ATTRIBUTE = ApiKeyAuthenticationFilter.class.getName() + ".API_TENANT";
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String EMISSION_POINT_HEADER = "X-Emission-Point-ID";

    private final ApiTenantResolver apiTenantResolver;

    public ApiKeyAuthenticationFilter(ApiTenantResolver apiTenantResolver) {
        this.apiTenantResolver = apiTenantResolver;
    }

    @Override
    public void doFilter(
            jakarta.servlet.ServletRequest servletRequest,
            jakarta.servlet.ServletResponse servletResponse,
            FilterChain filterChain
    ) throws ServletException, IOException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        try {
            ApiTenant apiTenant = apiTenantResolver
                    .resolve(resolveApiKey(request), request.getHeader(EMISSION_POINT_HEADER))
                    .orElse(null);

            if (apiTenant == null) {
                writeUnauthorized(response);
                return;
            }

            request.setAttribute(API_TENANT_ATTRIBUTE, apiTenant);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "api-key:" + apiTenant.tenantId(),
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_API_INGEST"))
            ));

            filterChain.doFilter(request, response);
        } catch (IllegalArgumentException ex) {
            writeUnauthorized(response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static String resolveApiKey(HttpServletRequest request) {
        String[] headers = {
                API_KEY_HEADER,
                "Api-Key",
                "api-key",
                "api_key",
                "X-Api-Key"
        };
        for (String header : headers) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        String queryKey = request.getParameter("api_key");
        if (queryKey != null && !queryKey.isBlank()) {
            return queryKey.trim();
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && !authorization.isBlank()) {
            String raw = authorization.trim();
            if (raw.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return raw.substring(7).trim();
            }
            if (raw.regionMatches(true, 0, "ApiKey ", 0, 7)) {
                return raw.substring(7).trim();
            }
        }
        return null;
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"X-API-Key invalida o punto de venta no autorizado\"}");
    }
}
