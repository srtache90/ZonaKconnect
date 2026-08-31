package com.zonak.portal.config;

import jakarta.servlet.http.HttpSession;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class BackendClientConfig {

    private static final Logger log = LoggerFactory.getLogger(BackendClientConfig.class);

    @Bean
    public WebClient coreGoWebClient(
            WebClient.Builder builder,
            @Value("${services.core-go.base-url}") String coreGoBaseUrl
    ) {
        return builder
                .baseUrl(coreGoBaseUrl)
                .filter(tenantHeadersFilter())
                .build();
    }

    @Bean
    public WebClient dianNetWebClient(
            WebClient.Builder builder,
            @Value("${services.dian-net.base-url}") String dianNetBaseUrl
    ) {
        String normalized = normalizeServiceBaseUrl(dianNetBaseUrl, "http://localhost:8090");
        log.info("WebClient DIAN_NET base-url={}", normalized);
        return builder
                .baseUrl(normalized)
                .build();
    }

    static String normalizeServiceBaseUrl(String configured, String fallback) {
        String candidate = StringUtils.hasText(configured) ? configured.trim() : fallback;
        if (candidate.endsWith("/")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        URI uri = URI.create(candidate);
        if (!StringUtils.hasText(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
            throw new IllegalStateException("URL de servicio inválida: " + configured);
        }
        if (uri.getPort() <= 0) {
            int inferredPort = inferDefaultPort(uri);
            uri = URI.create(uri.getScheme() + "://" + uri.getHost() + ":" + inferredPort);
        }
        return uri.toString();
    }

    private static int inferDefaultPort(URI uri) {
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return 443;
        }
        String host = uri.getHost();
        if ("localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "[::1]".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host)) {
            return 8090;
        }
        if ("dian-net".equalsIgnoreCase(host) || "core-go".equalsIgnoreCase(host)) {
            return 8080;
        }
        return 80;
    }

    private ExchangeFilterFunction tenantHeadersFilter() {
        return (request, next) -> {
            ClientRequest.Builder outgoing = ClientRequest.from(request);
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

            if (attrs != null) {
                HttpSession session = attrs.getRequest().getSession(false);
                if (session != null) {
                    addHeader(outgoing, "X-Tenant-ID", session.getAttribute("tenantId"));
                    addHeader(outgoing, "X-Emission-Point-ID", session.getAttribute("emissionPointId"));
                }
            }

            return next.exchange(outgoing.build());
        };
    }

    private static void addHeader(ClientRequest.Builder request, String name, Object value) {
        if (value != null) {
            request.header(name, value.toString());
        }
    }
}
