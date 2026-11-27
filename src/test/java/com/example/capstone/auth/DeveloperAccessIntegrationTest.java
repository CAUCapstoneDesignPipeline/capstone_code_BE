package com.example.capstone.auth;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.capstone.auth.service.DeveloperAccessService;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;
import com.example.capstone.user.domain.AppUser;
import com.example.capstone.user.repository.AppUserRepository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = "capstone.developer.emails= Dev@Example.com ,second@example.com")
@ActiveProfiles("test")
@Testcontainers
class DeveloperAccessIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15");

    @Autowired
    DeveloperAccessService access;
    @Autowired
    AppUserRepository users;

    @Test
    void 목록에_있는_이메일은_대소문자와_공백을_무시하고_통과한다() {
        assertDoesNotThrow(() -> access.require(user("dev@example.com")));
        assertDoesNotThrow(() -> access.require(user("SECOND@example.com")));
    }

    @Test
    void 목록에_없거나_이메일이_없거나_없는_사용자는_403이다() {
        assertForbidden(user("other@example.com"));
        assertForbidden(user(null));
        assertForbidden(UUID.randomUUID());
    }

    private void assertForbidden(UUID userId) {
        ApiException exception = assertThrows(ApiException.class, () -> access.require(userId));
        assertEquals(ErrorCode.FORBIDDEN, exception.errorCode());
    }

    private UUID user(String email) {
        return users.saveAndFlush(new AppUser(email, "사용자", Instant.now())).getId();
    }
}
