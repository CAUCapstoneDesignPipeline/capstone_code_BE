package com.example.capstone.auth.config;

import java.net.URI;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, AuthenticationErrorHandler errors,
            Environment environment) throws Exception {
        http.cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(HttpMethod.GET,
                            "/api/v1/health", "/actuator/health", "/actuator/health/**").permitAll();
                    requests.requestMatchers(HttpMethod.GET, "/api/auth/providers",
                            "/api/auth/oauth2/{provider}", "/api/auth/oauth2/{provider}/callback").permitAll();
                    requests.requestMatchers(HttpMethod.POST,
                            "/api/auth/refresh", "/api/auth/logout", "/api/auth/dev/token").permitAll();
                    if (environment.matchesProfiles("local", "test")) {
                        requests.requestMatchers(HttpMethod.GET,
                                "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**",
                                "/v3/api-docs.yaml").permitAll();
                    }
                    requests.anyRequest().authenticated();
                })
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors))
                .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(errors));
        return http.build();
    }

    @Bean
    UrlBasedCorsConfigurationSource corsConfigurationSource(AuthProperties properties) {
        URI app;
        try {
            app = URI.create(properties.appUrl());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("CAPSTONE_APP_URL must be an HTTP(S) origin");
        }
        if (app.getHost() == null || !("http".equals(app.getScheme()) || "https".equals(app.getScheme()))
                || app.getRawUserInfo() != null || app.getRawQuery() != null || app.getRawFragment() != null
                || (app.getRawPath() != null && !app.getRawPath().isEmpty())) {
            throw new IllegalStateException("CAPSTONE_APP_URL must be an HTTP(S) origin without a path");
        }
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(properties.appUrl()));
        cors.setAllowCredentials(true);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
