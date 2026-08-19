package com.zonak.portal.service;

import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class DianXmlDebugClient {
    private final WebClient dianNetWebClient;

    public DianXmlDebugClient(@Qualifier("dianNetWebClient") WebClient dianNetWebClient) {
        this.dianNetWebClient = dianNetWebClient;
    }

    public Mono<Map<String, Object>> latestMetadata() {
        return dianNetWebClient
                .get()
                .uri("/api/v1/debug/xml/latest")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<>() {
                });
    }

    public Mono<String> latestXml(String stage) {
        return dianNetWebClient
                .get()
                .uri("/api/v1/debug/xml/latest/{stage}", stage)
                .accept(MediaType.APPLICATION_XML)
                .retrieve()
                .bodyToMono(String.class);
    }
}
