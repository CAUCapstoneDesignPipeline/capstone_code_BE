package com.example.capstone.auth.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import com.example.capstone.auth.dto.request.DevTokenRequest;
import com.example.capstone.auth.dto.response.TokenResponse;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;

@Service
public class DevTokenService {
    private final IdentityLoginService identities;
    private final AccessTokenService tokens;
    private final CurrentUserService users;
    public DevTokenService(IdentityLoginService identities, AccessTokenService tokens, CurrentUserService users) {
        this.identities = identities;
        this.tokens = tokens;
        this.users = users;
    }
    public TokenResponse issue(DevTokenRequest request) {
        String email = request.email() == null ? "dev@capstone.local" : request.email().trim().toLowerCase(Locale.ROOT);
        String name = request.displayName() == null ? "개발자" : Normalizer.normalize(request.displayName().trim(), Normalizer.Form.NFC);
        if (email.isEmpty() || email.length() > 254 || name.isEmpty() || name.codePointCount(0, name.length()) > 100) {
            String field = email.isEmpty() || email.length() > 254 ? "email" : "displayName";
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "입력값을 확인해 주세요.",
                    Map.of("fields", List.of(Map.of("field", field, "reason", "invalid"))));
        }
        UUID id = identities.login("dev", email, email, false, name, true);
        return new TokenResponse(tokens.issue(id), "Bearer", AccessTokenService.LIFETIME.toSeconds(), users.getMe(id));
    }
}
