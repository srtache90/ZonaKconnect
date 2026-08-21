package com.zonak.portal.security;

import com.zonak.portal.auth.ApiKeyAuthenticationFilter;
import com.zonak.portal.auth.JwtCookieAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyAuthenticationFilterRegistration(
            ApiKeyAuthenticationFilter filter
    ) {
        FilterRegistrationBean<ApiKeyAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<JwtCookieAuthenticationFilter> jwtCookieAuthenticationFilterRegistration(
            JwtCookieAuthenticationFilter filter
    ) {
        FilterRegistrationBean<JwtCookieAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @Order(0)
    public SecurityFilterChain sapSoapSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/ws/**", "/dispapeles/**", "/api/v1/ingest/sap")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .build();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain ingestApiSecurityFilterChain(
            HttpSecurity http,
            ApiKeyAuthenticationFilter apiKeyAuthenticationFilter
    ) throws Exception {
        return http
                .securityMatcher("/api/v1/ingest/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().hasRole("API_INGEST")
                )
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"error\":\"API Key requerida\"}");
                }))
                .addFilterBefore(apiKeyAuthenticationFilter, AuthorizationFilter.class)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain portalSecurityFilterChain(
            HttpSecurity http,
            JwtCookieAuthenticationFilter jwtCookieAuthenticationFilter
    ) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/logout", "/error", "/webjars/**", "/css/**", "/js/**", "/images/**", "/fonts/**").permitAll()
                        .requestMatchers("/portal/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/portal/facturacion/manual").hasAnyRole("ADMIN", "EMISOR", "OPERADOR")
                        .requestMatchers(HttpMethod.POST, "/portal/facturacion/manual/**").hasAnyRole("ADMIN", "EMISOR", "OPERADOR")
                        .requestMatchers(HttpMethod.POST, "/portal/invoices/emit", "/portal/invoices/*/reemit").hasAnyRole("ADMIN", "EMISOR", "OPERADOR")
                        .requestMatchers(HttpMethod.POST, "/portal/recepcion/**").hasAnyRole("ADMIN", "RECEPTOR")
                        .requestMatchers(HttpMethod.GET, "/portal/recepcion/**").hasAnyRole("ADMIN", "RECEPTOR", "CONSULTA")
                        .requestMatchers("/portal/**").authenticated()
                        .requestMatchers("/api/v1/recepcion/**").authenticated()
                        .requestMatchers("/").authenticated()
                        .anyRequest().permitAll()
                )
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) ->
                        response.sendRedirect("/login")
                ))
                .addFilterBefore(jwtCookieAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .build();
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    public SecurityWebFilterChain portalReactiveSecurityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/login", "/logout", "/error", "/webjars/**", "/css/**", "/js/**", "/images/**", "/fonts/**").permitAll()
                        .pathMatchers("/ws/**", "/dispapeles/**", "/api/v1/ingest/sap").permitAll()
                        .pathMatchers("/api/v1/ingest/**").hasRole("API_INGEST")
                        .pathMatchers("/portal/admin/**").hasRole("ADMIN")
                        .pathMatchers("/portal/facturacion/manual").hasAnyRole("ADMIN", "EMISOR", "OPERADOR")
                        .pathMatchers("/portal/facturacion/manual/**").hasAnyRole("ADMIN", "EMISOR", "OPERADOR")
                        .pathMatchers("/portal/invoices/emit").hasAnyRole("ADMIN", "EMISOR", "OPERADOR")
                        .pathMatchers("/portal/**").authenticated()
                        .pathMatchers("/api/v1/recepcion/**").authenticated()
                        .pathMatchers("/").authenticated()
                        .anyExchange().permitAll()
                )
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .build();
    }
}
