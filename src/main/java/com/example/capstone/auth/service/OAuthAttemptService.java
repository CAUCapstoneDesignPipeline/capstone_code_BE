package com.example.capstone.auth.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.capstone.auth.domain.OAuthAttempt;
import com.example.capstone.auth.repository.OAuthAttemptRepository;

@Service
public class OAuthAttemptService {
    public static final Duration LIFETIME = Duration.ofMinutes(5);
    private final OAuthAttemptRepository attempts;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    public OAuthAttemptService(OAuthAttemptRepository attempts, Clock clock) { this.attempts = attempts; this.clock = clock; }
    @Transactional
    public Start start(String returnTo) {
        String state = random(), cookie = random(), nonce = random(), verifier = random();
        attempts.deleteExpired(clock.instant());
        attempts.save(new OAuthAttempt(RefreshTokenService.hash(state), RefreshTokenService.hash(cookie),
                nonce, verifier, returnTo, clock.instant().plus(LIFETIME)));
        return new Start(state, cookie, nonce, verifier);
    }
    @Transactional
    public OAuthAttempt claim(String state, String cookie) {
        if (state == null || cookie == null || state.length() > 128 || cookie.length() > 128) { return null; }
        OAuthAttempt attempt = attempts.findByStateHash(RefreshTokenService.hash(state)).orElse(null);
        return attempt != null && attempt.claim(RefreshTokenService.hash(cookie), clock.instant()) ? attempt : null;
    }
    private String random() {
        byte[] value = new byte[32]; random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
    public record Start(String state, String cookie, String nonce, String verifier) {
        @Override public String toString() { return "OAuthStart[values=<redacted>]"; }
    }
}
