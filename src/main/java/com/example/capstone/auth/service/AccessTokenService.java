package com.example.capstone.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.capstone.auth.config.AuthProperties;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;
import com.example.capstone.user.repository.AppUserRepository;
import com.example.capstone.user.repository.UserIdentityRepository;

@Service
public class AccessTokenService {

    public static final Duration LIFETIME = Duration.ofMinutes(30);

    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;
    private final AppUserRepository users;
    private final UserIdentityRepository identities;

    public AccessTokenService(JwtEncoder encoder, AuthProperties properties, Clock clock,
            AppUserRepository users, UserIdentityRepository identities) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
        this.users = users;
        this.identities = identities;
    }

    @Transactional(readOnly = true)
    public String issue(UUID userId) {
        if (!users.existsById(userId)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다.");
        }
        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer(properties.jwt().issuer())
                .subject(userId.toString()).issuedAt(now).expiresAt(now.plus(LIFETIME));
        // Derive this from persisted identity, including when a later refresh issues a token.
        if (identities.existsByUserIdAndProvider(userId, "dev")) {
            claims.claim("dev", true);
        }
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }
}
