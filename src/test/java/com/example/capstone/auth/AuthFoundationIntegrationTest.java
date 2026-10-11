package com.example.capstone.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.example.capstone.auth.service.AccessTokenService;
import com.example.capstone.user.domain.AppUser;
import com.example.capstone.user.domain.UserIdentity;
import com.example.capstone.user.repository.AppUserRepository;
import com.example.capstone.user.repository.UserIdentityRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AuthFoundationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private UserIdentityRepository identities;
    @Autowired
    private AccessTokenService tokens;
    @Autowired
    private JwtDecoder decoder;
    @Autowired
    private JwtEncoder encoder;

    private AppUser user;
    private AppUser other;

    @BeforeEach
    void createPersistedIdentities() {
        Instant now = Instant.now();
        user = users.saveAndFlush(new AppUser(null, "내 사용자", now));
        other = users.saveAndFlush(new AppUser("other@example.com", "다른 사용자", now));
        identities.saveAndFlush(new UserIdentity(user.getId(), "google", "google-subject-a", null, true, now));
        identities.saveAndFlush(new UserIdentity(other.getId(), "dev", "dev-subject-b", other.getEmail(), false, now));
    }

    @AfterEach
    void removeOnlyTestFixtures() {
        identities.deleteAll();
        users.deleteAll();
    }

    @Test
    void returnsMeFromVerifiedSubjectAndPreservesNullableEmailWithoutSession() throws Exception {
        String token = tokens.issue(user.getId());
        Jwt jwt = decoder.decode(token);
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("capstone-be");
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(30));
        assertThat(jwt.hasClaim("dev")).isFalse();

        mvc.perform(get("/api/auth/me").param("userId", other.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.displayName").value("내 사용자"))
                .andExpect(jsonPath("$").value(hasKey("email")))
                .andExpect(content().json("""
                        {"id":"%s","email":null,"displayName":"내 사용자","providers":["google"]}
                        """.formatted(user.getId())))
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());

        String devToken = tokens.issue(other.getId());
        assertThat(decoder.decode(devToken).getClaimAsBoolean("dev")).isTrue();
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + devToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0]").value("dev"));
    }

    @Test
    void rejectsMissingExpiredTamperedAndInvalidIdentityTokensWithCommonError() throws Exception {
        assertUnauthenticated(null);
        Instant now = Instant.now().minusSeconds(2);
        JwtClaimsSet valid = claims(user.getId().toString(), now, now.plusSeconds(1800));
        String validToken = sign(valid);
        String[] parts = validToken.split("\\.");
        String forgedSignature = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        for (String token : List.of("malformed", parts[0] + "." + parts[1] + "." + forgedSignature,
                sign(claims(user.getId().toString(), now.minusSeconds(1800), now.minusSeconds(1))),
                sign(claims("invalid-subject", now, now.plusSeconds(1800))),
                sign(JwtClaimsSet.from(valid).issuer("untrusted-issuer").build()),
                sign(JwtClaimsSet.from(valid).claims(values -> values.remove("exp")).build()),
                sign(JwtClaimsSet.from(valid).claims(values -> values.remove("iat")).build()),
                sign(claims(UUID.randomUUID().toString(), now, now.plusSeconds(1800))))) {
            assertUnauthenticated(token);
        }
    }

    @Test
    void allowsHealthAndAppPreflightButProtectsOtherApiPaths() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
        mvc.perform(get("/api/topics")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(options("/api/auth/me").header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        mvc.perform(get("/api/auth/me").header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
        mvc.perform(options("/api/auth/me").header(HttpHeaders.ORIGIN, "https://untrusted.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    private static JwtClaimsSet claims(String subject, Instant issuedAt, Instant expiresAt) {
        return JwtClaimsSet.builder().issuer("capstone-be").subject(subject)
                .issuedAt(issuedAt).expiresAt(expiresAt).build();
    }

    private String sign(JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private void assertUnauthenticated(String token) throws Exception {
        var request = get("/api/auth/me");
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"로그인이 필요합니다.\"}}"))
                .andExpect(jsonPath("$.error.details").doesNotExist());
    }
}
