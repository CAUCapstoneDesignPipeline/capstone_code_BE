package com.example.capstone.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("capstone.auth")
public record AuthProperties(String appUrl, Jwt jwt, Google google, String allowedEmails) {

    public record Google(boolean enabled, String clientId, String clientSecret, String redirectUri,
            String authorizationUri, String tokenUri, String jwksUri) {
        @Override
        public String toString() { return "Google[enabled=" + enabled + ", credentials=<redacted>]"; }
    }

    public record Jwt(String secret, String issuer) {

        @Override
        public String toString() {
            return "Jwt[secret=<redacted>, issuer=" + issuer + "]";
        }
    }
}
