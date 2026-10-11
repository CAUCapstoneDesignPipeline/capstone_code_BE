package com.example.capstone.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.capstone.auth.config.AuthEnvironment;
import com.example.capstone.auth.domain.RefreshToken;
import com.example.capstone.auth.dto.response.TokenResponse;
import com.example.capstone.auth.repository.RefreshTokenRepository;
import com.example.capstone.user.repository.AppUserRepository;
import com.example.capstone.user.repository.UserIdentityRepository;

@Service
public class RefreshTokenService {
    private final RefreshTokenRepository refreshTokens;
    private final AppUserRepository users;
    private final UserIdentityRepository identities;
    private final AccessTokenService accessTokens;
    private final CurrentUserService currentUsers;
    private final Clock clock;
    private final Environment environment;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository refreshTokens, AppUserRepository users,
            UserIdentityRepository identities, AccessTokenService accessTokens,
            CurrentUserService currentUsers, Clock clock, Environment environment) {
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.identities = identities;
        this.accessTokens = accessTokens;
        this.currentUsers = currentUsers;
        this.clock = clock;
        this.environment = environment;
    }

    @Transactional
    public String issue(UUID userId) {
        users.lockById(userId).orElseThrow();
        return create(userId, UUID.randomUUID(), clock.instant()).raw();
    }

    @Transactional
    public Rotation rotate(String raw) {
        UUID userId = userId(raw);
        if (userId == null || users.lockById(userId).isEmpty()) {
            return Rotation.denied();
        }
        // Load only after the shared user lock; a pre-lock managed entity could be stale.
        RefreshToken token = refreshTokens.findByTokenHash(hash(raw)).orElse(null);
        Instant now = clock.instant();
        if (token == null) {
            return Rotation.denied();
        }
        if (token.wasReplaced()) {
            refreshTokens.revokeFamily(userId, token.getFamilyId(), now);
            // Return a result, not an exception: family revocation must commit before HTTP 401.
            return Rotation.denied();
        }
        if (!token.isUsable(now) || (!AuthEnvironment.isDevelopment(environment)
                && identities.existsByUserIdAndProvider(userId, "dev"))) {
            return Rotation.denied();
        }
        Issued next = create(userId, token.getFamilyId(), now);
        token.replaceWith(next.token().getId(), now);
        refreshTokens.flush();
        return new Rotation(next.raw(), new TokenResponse(accessTokens.issue(userId), "Bearer",
                AccessTokenService.LIFETIME.toSeconds(), currentUsers.getMe(userId)));
    }

    @Transactional
    public void logout(String raw) {
        UUID userId = userId(raw);
        if (userId == null || users.lockById(userId).isEmpty()) {
            return;
        }
        refreshTokens.findByTokenHash(hash(raw))
                .ifPresent(token -> refreshTokens.revokeFamily(userId, token.getFamilyId(), clock.instant()));
    }

    private UUID userId(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 128) {
            return null;
        }
        return refreshTokens.findUserIdByHash(hash(raw)).orElse(null);
    }

    private Issued create(UUID userId, UUID familyId, Instant now) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        RefreshToken token = refreshTokens.saveAndFlush(new RefreshToken(userId, familyId, hash(raw), now));
        return new Issued(raw, token);
    }

    public static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private record Issued(String raw, RefreshToken token) {
    }

    public record Rotation(String refreshToken, TokenResponse response) {
        public static Rotation denied() { return new Rotation(null, null); }
        public boolean successful() { return response != null; }
        @Override
        public String toString() { return "Rotation[successful=" + successful() + "]"; }
    }
}
