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
                    .resolve(request.getHeader(API_KEY_HEADER), request.getHeader(EMISSION_POINT_HEADER))
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

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"X-API-Key invalida o punto de venta no autorizado\"}");
    }
}
