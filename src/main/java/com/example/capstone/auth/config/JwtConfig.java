package com.example.capstone.auth.config;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class JwtConfig {

    @Bean
    Clock authClock() {
        return Clock.systemUTC();
    }

    @Bean
    SecretKey jwtSigningKey(AuthProperties properties) {
        String secret = properties.jwt() == null ? null : properties.jwt().secret();
        if (secret == null || secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("CAPSTONE_JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        if (properties.jwt().issuer() == null || properties.jwt().issuer().isBlank()) {
            throw new IllegalStateException("JWT issuer must be configured");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, AuthProperties properties,
            Environment environment, Clock authClock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(authClock);
        // A mixed production/development profile must never enable development credentials.
        String[] profiles = environment.getActiveProfiles();
        boolean development = profiles.length > 0 && Arrays.stream(profiles)
                .allMatch(profile -> profile.equals("local") || profile.equals("test"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps,
                new JwtIssuerValidator(properties.jwt().issuer()),
                jwt -> validateClaims(jwt, development, authClock.instant())));
        return decoder;
    }

    private static OAuth2TokenValidatorResult validateClaims(Jwt jwt, boolean development, Instant now) {
        try {
            String subject = jwt.getSubject();
            if (subject == null || !UUID.fromString(subject).toString().equals(subject)) {
                return invalidClaims();
            }
            Instant issuedAt = jwt.getIssuedAt();
            Instant expiresAt = jwt.getExpiresAt();
            if (issuedAt == null || expiresAt == null || issuedAt.isAfter(now)
                    || !expiresAt.isAfter(now) || !expiresAt.isAfter(issuedAt)
                    || expiresAt.isAfter(issuedAt.plus(Duration.ofMinutes(30)))) {
                return invalidClaims();
            }
            if (jwt.hasClaim("dev") && (!development || !Boolean.TRUE.equals(jwt.getClaims().get("dev")))) {
                return invalidClaims();
            }
            return OAuth2TokenValidatorResult.success();
        } catch (IllegalArgumentException exception) {
            return invalidClaims();
        }
    }

    private static OAuth2TokenValidatorResult invalidClaims() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid access token", null));
    }
}
