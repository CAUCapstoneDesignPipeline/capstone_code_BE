package com.example.capstone.auth.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.capstone.auth.dto.response.MeResponse;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;
import com.example.capstone.user.domain.AppUser;
import com.example.capstone.user.domain.UserIdentity;
import com.example.capstone.user.repository.AppUserRepository;
import com.example.capstone.user.repository.UserIdentityRepository;

@Service
public class CurrentUserService {

    private final AppUserRepository users;
    private final UserIdentityRepository identities;

    public CurrentUserService(AppUserRepository users, UserIdentityRepository identities) {
        this.users = users;
        this.identities = identities;
    }

    @Transactional(readOnly = true)
    public MeResponse getMe(UUID userId) {
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다."));
        return new MeResponse(user.getId(), user.getEmail(), user.getDisplayName(),
                identities.findByUserIdOrderByProvider(userId).stream()
                        .map(UserIdentity::getProvider).toList());
    }
}
