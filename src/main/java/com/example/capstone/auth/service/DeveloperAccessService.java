package com.example.capstone.auth.service;

import java.util.Set;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.capstone.auth.config.DeveloperProperties;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;
import com.example.capstone.user.domain.AppUser;
import com.example.capstone.user.repository.AppUserRepository;

/** 개발자 전용 API의 권한 확인. 로그인한 계정의 이메일이 설정된 목록에 없으면 403이다. */
@Service
@EnableConfigurationProperties(DeveloperProperties.class)
public class DeveloperAccessService {

    private final AppUserRepository users;
    private final Set<String> emails;

    public DeveloperAccessService(AppUserRepository users, DeveloperProperties properties) {
        this.users = users;
        this.emails = properties.normalizedEmails();
    }

    @Transactional(readOnly = true)
    public void require(UUID userId) {
        String email = users.findById(userId).map(AppUser::getEmail).map(DeveloperProperties::normalize).orElse("");
        if (email.isEmpty() || !emails.contains(email)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "개발자 계정만 사용할 수 있습니다.");
        }
    }
}
