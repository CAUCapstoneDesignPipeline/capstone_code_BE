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
import com.example.capstone.user.domain.AppUser;
import com.example.capstone.user.repository.AppUserRepository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 운영처럼 CAPSTONE_DEVELOPER_EMAILS 없이 배포 도구가 넣는 capstone.auth.allowed-emails만 있을 때. */
@SpringBootTest(properties = "capstone.auth.allowed-emails=legacy@example.com,second@example.com")
@ActiveProfiles("test")
@Testcontainers
class DeveloperAccessFallbackIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15");

    @Autowired
    DeveloperAccessService access;
    @Autowired
    AppUserRepository users;

    @Test
    void 개발자_목록이_없으면_배포_도구가_넣는_허용_목록을_쓴다() {
        assertDoesNotThrow(() -> access.require(user("second@example.com")));
        assertThrows(ApiException.class, () -> access.require(user("other@example.com")));
    }

    private UUID user(String email) {
        return users.saveAndFlush(new AppUser(email, "사용자", Instant.now())).getId();
    }
}
