package com.example.capstone.auth.controller;

import java.net.URI;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.example.capstone.auth.config.AuthProperties;
import com.example.capstone.auth.service.AuthCookieService;
import com.example.capstone.auth.service.GoogleOAuthClient;
import com.example.capstone.auth.service.OAuthAttemptService;
import com.example.capstone.auth.service.OAuthLoginService;
import com.example.capstone.auth.service.ReturnToService;

@RestController
public class OAuthController {
    public static final String STATE_COOKIE = "CAPSTONE_OAUTH";
    private final OAuthLoginService login;
    private final OAuthAttemptService attempts;
    private final GoogleOAuthClient google;
    private final AuthCookieService cookies;
    private final AuthProperties properties;
    public OAuthController(OAuthLoginService login, OAuthAttemptService attempts, GoogleOAuthClient google,
            AuthCookieService cookies, AuthProperties properties) {
        this.login = login; this.attempts = attempts; this.google = google; this.cookies = cookies; this.properties = properties;
    }
    @GetMapping("/api/auth/oauth2/{provider}")
    public ResponseEntity<Void> start(@PathVariable String provider, @RequestParam(required = false) String returnTo) {
        login.requireProvider(provider);
        String safe = ReturnToService.validate(returnTo);
        var attempt = attempts.start(safe);
        return ResponseEntity.status(302).location(URI.create(google.authorization(attempt)))
                .header(HttpHeaders.SET_COOKIE, stateCookie(attempt.cookie(), OAuthAttemptService.LIFETIME)).build();
    }
    @GetMapping("/api/auth/oauth2/{provider}/callback")
    public ResponseEntity<Void> callback(@PathVariable String provider, @RequestParam(required = false) String code,
            @RequestParam(required = false) String state, @RequestParam(required = false) String error,
            @CookieValue(name = STATE_COOKIE, required = false) String cookie) {
        login.requireProvider(provider);
        var result = login.complete(state, cookie, code, error);
        String path = result.error() == null ? "/auth/callback?returnTo=" + GoogleOAuthClient.encode(result.returnTo())
                : "/login?error=" + result.error();
        var response = ResponseEntity.status(302).location(URI.create(properties.appUrl() + path))
                .header(HttpHeaders.SET_COOKIE, stateCookie("", Duration.ZERO));
        if (result.error() == null) { response.header(HttpHeaders.SET_COOKIE, cookies.refresh(result.refreshToken())); }
        return response.build();
    }
    private String stateCookie(String value, Duration lifetime) {
        return cookies.cookie(STATE_COOKIE, value, "/api/auth/oauth2", "Lax", lifetime);
    }
}
