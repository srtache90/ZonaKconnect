package com.zonak.portal.config;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class BackendClientConfig {

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
        return builder
                .baseUrl(dianNetBaseUrl)
                .build();
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
