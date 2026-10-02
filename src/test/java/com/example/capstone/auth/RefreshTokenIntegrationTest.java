package com.example.capstone.auth;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.http.Cookie;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import com.example.capstone.auth.config.AuthProperties;
import com.example.capstone.auth.service.AccessTokenService;
import com.example.capstone.auth.service.CurrentUserService;
import com.example.capstone.auth.service.AuthCookieService;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import com.example.capstone.auth.repository.RefreshTokenRepository;
import com.example.capstone.auth.service.RefreshTokenService;
import com.example.capstone.user.domain.AppUser;
import com.example.capstone.user.domain.UserIdentity;
import com.example.capstone.user.repository.AppUserRepository;
import com.example.capstone.user.repository.UserIdentityRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class RefreshTokenIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15");
    @Autowired private MockMvc mvc;
    @Autowired private RefreshTokenService service;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private AppUserRepository users;
    @Autowired private UserIdentityRepository identities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtDecoder decoder;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private AccessTokenService accessTokens;
    @Autowired private CurrentUserService currentUsers;
    @Autowired private Clock clock;
    @Autowired private AuthProperties properties;
    private AppUser user;

    @BeforeEach
    void fixture() {
        Instant now = Instant.now();
        user = users.saveAndFlush(new AppUser("dev@example.com", "개발자", now));
        identities.saveAndFlush(new UserIdentity(user.getId(), "dev", "refresh-dev", user.getEmail(), false, now));
    }

    @AfterEach
    void cleanup() {
        refreshTokens.deleteAll();
        identities.deleteAll();
        users.deleteAll();
    }

    @Test
    void rotatesHashAndCookieAndCommitsFamilyRevocationBeforeUnauthorized() throws Exception {
        String first = service.issue(user.getId());
        assertThat(jdbc.queryForObject("select token_hash from refresh_token", String.class))
                .isEqualTo(RefreshTokenService.hash(first)).isNotEqualTo(first);
        assertThat(jdbc.queryForObject("select extract(epoch from expires_at-created_at)::bigint from refresh_token", Long.class))
                .isEqualTo(14 * 24 * 60 * 60L);
        var result = mvc.perform(post("/api/auth/refresh").header("Origin", "http://localhost:5173")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer expired-token-is-irrelevant")
                        .cookie(new Cookie("CAPSTONE_REFRESH", first)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(1800))
                .andExpect(jsonPath("$.user.id").value(user.getId().toString())).andReturn();
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("HttpOnly", "SameSite=Strict", "Path=/api/auth", "Max-Age=1209600")
                .doesNotContain("Secure");
        String next = setCookie.substring("CAPSTONE_REFRESH=".length(), setCookie.indexOf(';'));
        assertThat(next).isNotEqualTo(first);
        assertThat(decoder.decode(mapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText()).getClaimAsBoolean("dev")).isTrue();
        denied(first);
        denied(next);
        assertThat(jdbc.queryForObject("select count(*) from refresh_token where revoked_at is null", Long.class)).isZero();
    }

    @Test
    void onlyOneConcurrentRotationSucceedsAndReuseRevokesItsSuccessor() throws Exception {
        String raw = service.issue(user.getId());
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return service.rotate(raw); });
            var second = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return service.rotate(raw); });
            var results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(RefreshTokenService.Rotation::successful).count()).isEqualTo(1);
            String next = results.stream().filter(RefreshTokenService.Rotation::successful).findFirst().orElseThrow().refreshToken();
            assertThat(service.rotate(next).successful()).isFalse();
        }
    }

    @Test
    void rejectsExpiredTokensAndBothOriginFailuresWithoutChangingCookiesOrTokens() throws Exception {
        String raw = service.issue(user.getId());
        for (String path : List.of("/api/auth/refresh", "/api/auth/logout")) {
            mvc.perform(post(path).cookie(new Cookie("CAPSTONE_REFRESH", raw)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                    .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
            mvc.perform(post(path).header("Origin", "https://untrusted.example").cookie(new Cookie("CAPSTONE_REFRESH", raw)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                    .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        }
        assertThat(jdbc.queryForObject("select count(*) from refresh_token where revoked_at is not null", Long.class)).isZero();
        jdbc.update("update refresh_token set created_at = '2020-01-01T00:00:00Z', expires_at = '2020-01-02T00:00:00Z'");
        denied(raw);
        denied("unknown-token");
    }

    @Test
    void logoutIsIdempotentAndLeavesOtherDeviceUsable() throws Exception {
        String device = service.issue(user.getId());
        String other = service.issue(user.getId());
        mvc.perform(post("/api/auth/logout").header("Origin", "http://localhost:5173")
                        .cookie(new Cookie("CAPSTONE_REFRESH", device)))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));
        denied(device);
        assertThat(service.rotate(other).successful()).isTrue();
        mvc.perform(post("/api/auth/logout").header("Origin", "http://localhost:5173"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
    }

    @Test
    void productionRejectsDevRefreshAndUsesSecureCookies() {
        String raw = service.issue(user.getId());
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        RefreshTokenService productionService = new RefreshTokenService(refreshTokens, users, identities,
                accessTokens, currentUsers, clock, production);
        var result = new TransactionTemplate(transactions).execute(status -> productionService.rotate(raw));
        assertThat(result.successful()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from refresh_token", Long.class)).isEqualTo(1);
        assertThat(new AuthCookieService(properties, production).refresh(raw))
                .contains("Secure", "HttpOnly", "SameSite=Strict", "Path=/api/auth");
    }

    private void denied(String raw) throws Exception {
        mvc.perform(post("/api/auth/refresh").header("Origin", "http://localhost:5173")
                        .cookie(new Cookie("CAPSTONE_REFRESH", raw)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }
}
