package com.example.capstone.auth.service;

import java.net.URI;
import java.time.Duration;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import com.example.capstone.auth.config.AuthEnvironment;
import com.example.capstone.auth.config.AuthProperties;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;

@Service
public class AuthCookieService {
    public static final String REFRESH_COOKIE = "CAPSTONE_REFRESH";
    private final AuthProperties properties;
    private final boolean secure;

    public AuthCookieService(AuthProperties properties, Environment environment) {
        this.properties = properties;
        this.secure = !(AuthEnvironment.isDevelopment(environment)
                && "http".equals(URI.create(properties.appUrl()).getScheme()));
    }

    public void requireOrigin(String origin) {
        if (!properties.appUrl().equals(origin)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "허용된 앱에서 요청해 주세요.");
        }
    }

    public String refresh(String raw) {
        return cookie(REFRESH_COOKIE, raw, "/api/auth", "Strict", Duration.ofDays(14));
    }

    public String clearRefresh() {
        return cookie(REFRESH_COOKIE, "", "/api/auth", "Strict", Duration.ZERO);
    }

    public String cookie(String name, String value, String path, String sameSite, Duration age) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(secure).path(path)
                .sameSite(sameSite).maxAge(age).build().toString();
    }
}
