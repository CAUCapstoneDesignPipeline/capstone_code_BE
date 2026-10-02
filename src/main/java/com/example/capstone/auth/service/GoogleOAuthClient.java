package com.example.capstone.auth.service;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import com.example.capstone.auth.config.AuthEnvironment;
import com.example.capstone.auth.config.AuthProperties;

@Service
public class GoogleOAuthClient {
    private final AuthProperties.Google config;
    private final ObjectMapper mapper;
    private final NimbusJwtDecoder decoder;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public GoogleOAuthClient(AuthProperties properties, ObjectMapper mapper, Environment environment, Clock clock) {
        this.config = properties.google(); this.mapper = mapper;
        if (config == null || !config.enabled()) { this.decoder = null; return; }
        if (empty(config.clientId()) || empty(config.clientSecret()) || empty(config.redirectUri())) {
            throw new IllegalStateException("Enabled Google login requires client ID, client secret and redirect URI");
        }
        boolean test = java.util.Arrays.equals(environment.getActiveProfiles(), new String[]{"test"});
        validateEndpoint(config.authorizationUri(), "https://accounts.google.com/o/oauth2/v2/auth", test);
        validateEndpoint(config.tokenUri(), "https://oauth2.googleapis.com/token", test);
        validateEndpoint(config.jwksUri(), "https://www.googleapis.com/oauth2/v3/certs", test);
        URI redirect = URI.create(config.redirectUri());
        if (redirect.getHost() == null || redirect.getUserInfo() != null || redirect.getQuery() != null
                || redirect.getFragment() != null || !"/api/auth/oauth2/google/callback".equals(redirect.getPath())
                || !("https".equals(redirect.getScheme()) || (AuthEnvironment.isDevelopment(environment)
                && "http".equals(redirect.getScheme()) && List.of("localhost", "127.0.0.1").contains(redirect.getHost())))) {
            throw new IllegalStateException("Invalid Google redirect URI");
        }
        this.decoder = NimbusJwtDecoder.withJwkSetUri(config.jwksUri()).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        this.decoder.setJwtValidator(jwt -> {
            boolean valid = List.of("https://accounts.google.com", "accounts.google.com").contains(jwt.getClaimAsString("iss"))
                    && jwt.getAudience().contains(config.clientId())
                    && (jwt.getAudience().size() == 1 || config.clientId().equals(jwt.getClaimAsString("azp")))
                    && (!jwt.hasClaim("azp") || config.clientId().equals(jwt.getClaimAsString("azp")))
                    && jwt.getExpiresAt() != null && jwt.getExpiresAt().isAfter(clock.instant())
                    && jwt.getIssuedAt() != null && !jwt.getIssuedAt().isAfter(clock.instant())
                    && !empty(jwt.getSubject()) && jwt.getSubject().length() <= 255;
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        });
    }
    public boolean enabled() { return decoder != null; }
    public String authorization(OAuthAttemptService.Start attempt) {
        try {
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(attempt.verifier().getBytes(StandardCharsets.US_ASCII)));
            return config.authorizationUri() + "?" + form(Map.of("client_id", config.clientId(), "redirect_uri", config.redirectUri(),
                    "response_type", "code", "scope", "openid email profile", "state", attempt.state(), "nonce", attempt.nonce(),
                    "code_challenge", challenge, "code_challenge_method", "S256"));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public Profile exchange(String code, String verifier, String nonce) throws Exception {
        if (empty(code) || code.length() > 4096) { throw new IllegalArgumentException("Invalid authorization code"); }
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.tokenUri())).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("client_id", config.clientId(), "client_secret", config.clientSecret(),
                        "redirect_uri", config.redirectUri(), "grant_type", "authorization_code", "code", code, "code_verifier", verifier)))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) { throw new IllegalArgumentException("Code exchange failed"); }
            byte[] bytes = body.readNBytes(65537);
            if (bytes.length > 65536) { throw new IllegalArgumentException("Provider response too large"); }
            String token = mapper.readTree(bytes).path("id_token").asString();
            var jwt = decoder.decode(token);
            if (!nonce.equals(jwt.getClaimAsString("nonce"))) { throw new IllegalArgumentException("Invalid nonce"); }
            String email = jwt.getClaimAsString("email");
            boolean verified = Boolean.TRUE.equals(jwt.getClaims().get("email_verified"));
            String name = jwt.getClaimAsString("name");
            if (empty(name)) { name = "사용자"; }
            name = Normalizer.normalize(name.trim(), Normalizer.Form.NFC);
            if (name.isEmpty()) { name = "사용자"; }
            if (name.codePointCount(0,name.length()) > 100) { name = name.substring(0,name.offsetByCodePoints(0,100)); }
            return new Profile(jwt.getSubject(), email, verified, name);
        }
    }
    private static void validateEndpoint(String value, String official, boolean test) {
        if (official.equals(value)) { return; }
        URI uri = URI.create(value);
        if (!test || !"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getUserInfo() != null) {
            throw new IllegalStateException("Google endpoints must use official HTTPS URLs outside isolated tests");
        }
    }
    private static boolean empty(String value) { return value == null || value.isBlank(); }
    public static String form(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
    }
    public static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    public record Profile(String subject, String email, boolean emailVerified, String name) {
        @Override public String toString() { return "GoogleProfile[values=<redacted>]"; }
    }
}
