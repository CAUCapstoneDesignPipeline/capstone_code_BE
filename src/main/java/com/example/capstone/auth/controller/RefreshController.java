package com.example.capstone.auth.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.capstone.auth.dto.response.TokenResponse;
import com.example.capstone.auth.service.AuthCookieService;
import com.example.capstone.auth.service.RefreshTokenService;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;

@RestController
@RequestMapping("/api/auth")
public class RefreshController {
    private final RefreshTokenService tokens;
    private final AuthCookieService cookies;

    public RefreshController(RefreshTokenService tokens, AuthCookieService cookies) {
        this.tokens = tokens;
        this.cookies = cookies;
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@RequestHeader(value = "Origin", required = false) String origin,
            @CookieValue(value = AuthCookieService.REFRESH_COOKIE, required = false) String raw,
            HttpServletResponse response) {
        cookies.requireOrigin(origin);
        RefreshTokenService.Rotation result = tokens.rotate(raw);
        if (!result.successful()) {
            response.addHeader(HttpHeaders.SET_COOKIE, cookies.clearRefresh());
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다.");
        }
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.refresh(result.refreshToken()));
        return result.response();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Origin", required = false) String origin,
            @CookieValue(value = AuthCookieService.REFRESH_COOKIE, required = false) String raw) {
        cookies.requireOrigin(origin);
        tokens.logout(raw);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clearRefresh()).build();
    }
}
