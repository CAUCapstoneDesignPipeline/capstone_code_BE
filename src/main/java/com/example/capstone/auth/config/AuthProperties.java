package com.example.capstone.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("capstone.auth")
public record AuthProperties(String appUrl, Jwt jwt) {

    public record Jwt(String secret, String issuer) {

        @Override
        public String toString() {
            return "Jwt[secret=<redacted>, issuer=" + issuer + "]";
        }
    }
}
