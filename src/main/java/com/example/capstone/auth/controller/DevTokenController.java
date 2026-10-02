package com.example.capstone.auth.controller;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.example.capstone.auth.config.DevTokenCondition;
import com.example.capstone.auth.dto.request.DevTokenRequest;
import com.example.capstone.auth.dto.response.TokenResponse;
import com.example.capstone.auth.service.AuthCookieService;
import com.example.capstone.auth.service.DevTokenService;
import com.example.capstone.auth.service.RefreshTokenService;

@RestController
@Conditional(DevTokenCondition.class)
public class DevTokenController {
    private final DevTokenService tokens;
    private final RefreshTokenService refresh;
    private final AuthCookieService cookies;
    public DevTokenController(DevTokenService tokens, RefreshTokenService refresh, AuthCookieService cookies) {
        this.tokens = tokens;
        this.refresh = refresh;
        this.cookies = cookies;
    }
    @PostMapping("/api/auth/dev/token")
    public ResponseEntity<TokenResponse> issue(@Valid @RequestBody(required = false) DevTokenRequest request) {
        if (request == null) { request = new DevTokenRequest(null, null, false); }
        TokenResponse response = tokens.issue(request);
        var result = ResponseEntity.ok();
        if (Boolean.TRUE.equals(request.issueRefreshCookie())) {
            result.header(HttpHeaders.SET_COOKIE, cookies.refresh(refresh.issue(response.user().id())));
        }
        return result.body(response);
    }
}
