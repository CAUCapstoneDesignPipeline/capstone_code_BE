package com.example.capstone.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import com.example.capstone.global.response.ErrorResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.net.URI;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.example.capstone.auth.config.AuthProperties;
import com.example.capstone.auth.service.AuthCookieService;
import com.example.capstone.auth.service.GoogleOAuthClient;
import com.example.capstone.auth.service.OAuthAttemptService;
import com.example.capstone.auth.service.OAuthLoginService;
import com.example.capstone.auth.service.ReturnToService;

@RestController
@Tag(name = "인증")
public class OAuthController {
    public static final String STATE_COOKIE = "CAPSTONE_OAUTH";
    private final OAuthLoginService login;
    private final OAuthAttemptService attempts;
    private final GoogleOAuthClient google;
    private final AuthCookieService cookies;
    private final AuthProperties properties;
    public OAuthController(OAuthLoginService login, OAuthAttemptService attempts, GoogleOAuthClient google,
            AuthCookieService cookies, AuthProperties properties) {
        this.login = login; this.attempts = attempts; this.google = google; this.cookies = cookies; this.properties = properties;
    }
    @Operation(summary = "소셜 로그인 시작",
            description = "FE에서 이 주소로 브라우저를 이동시키세요(location.assign 등). JSON을 받는 fetch 요청이 아니라 Google 로그인 화면으로 302 이동합니다. 현재 provider는 google만 지원하며 비활성 제공자는 404입니다. returnTo는 로그인 후 돌아갈 앱 내부 경로입니다. 생략 시 /를 사용하며 외부 URL·// 경로·역슬래시·제어 문자를 허용하지 않습니다. 임시 로그인 상태는 5분간 유효합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "302", description = "브라우저 이동 (Location 헤더, 본문 없음)", content = @Content),
        @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: 요청 형식·필드 값을 확인하세요. error.details.fields가 있으면 field와 reason으로 입력 오류를 표시합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "VALIDATION_FAILED", value = "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"로그인 후 이동 경로가 올바르지 않습니다.\",\"details\":{\"fields\":[{\"field\":\"returnTo\",\"reason\":\"invalid\"}]}}}")})),
        @ApiResponse(responseCode = "404", description = "NOT_FOUND: 지원하지 않거나 비활성화된 로그인 제공자입니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "NOT_FOUND", value = "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"지원하지 않는 로그인 방식입니다.\"}}")}))
    })
    @ResponseStatus(HttpStatus.FOUND)
    @GetMapping("/api/auth/oauth2/{provider}")
    public ResponseEntity<Void> start(@Parameter(description = "활성화된 소셜 로그인 제공자 ID", example = "google") @PathVariable String provider, @Parameter(description = "로그인 후 돌아갈 앱 내부 경로. /로 시작하며 최대 500 코드 포인트. 외부 URL 금지.", example = "/") @RequestParam(required = false) String returnTo) {
        login.requireProvider(provider);
        String safe = ReturnToService.validate(returnTo);
        var attempt = attempts.start(safe);
        return ResponseEntity.status(302).location(URI.create(google.authorization(attempt)))
                .header(HttpHeaders.SET_COOKIE, stateCookie(attempt.cookie(), OAuthAttemptService.LIFETIME)).build();
    }
    @Operation(summary = "소셜 로그인 콜백 (Google 호출용)",
            description = "Google이 로그인 결과를 전달하는 주소입니다. FE가 직접 호출하거나 code·state를 생성하지 않습니다. 성공하면 refresh 쿠키를 설정하고 CAPSTONE_APP_URL/auth/callback?returnTo=...로 이동합니다. FE 콜백 화면에서 credentials: include로 POST /api/auth/refresh를 호출해 액세스 토큰을 받으세요. 실패하면 /login?error=...로 이동합니다. OAuth 실패는 JSON 401 대신 로그인 화면 리다이렉트로 전달됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "302", description = "브라우저 이동 (Location 헤더, 본문 없음)", content = @Content),
        @ApiResponse(responseCode = "404", description = "NOT_FOUND: 지원하지 않거나 비활성화된 로그인 제공자입니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "NOT_FOUND", value = "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"지원하지 않는 로그인 방식입니다.\"}}")}))
    })
    @ResponseStatus(HttpStatus.FOUND)
    @GetMapping("/api/auth/oauth2/{provider}/callback")
    public ResponseEntity<Void> callback(@Parameter(description = "활성화된 소셜 로그인 제공자 ID", example = "google") @PathVariable String provider, @RequestParam(required = false) String code,
            @RequestParam(required = false) String state, @RequestParam(required = false) String error,
            @Parameter(hidden = true) @CookieValue(name = STATE_COOKIE, required = false) String cookie) {
        login.requireProvider(provider);
        var result = login.complete(state, cookie, code, error);
        String path = result.error() == null ? "/auth/callback?returnTo=" + GoogleOAuthClient.encode(result.returnTo())
                : "/login?error=" + result.error();
        var response = ResponseEntity.status(302).location(URI.create(properties.appUrl() + path))
                .header(HttpHeaders.SET_COOKIE, stateCookie("", Duration.ZERO));
        if (result.error() == null) { response.header(HttpHeaders.SET_COOKIE, cookies.refresh(result.refreshToken())); }
        return response.build();
    }
    private String stateCookie(String value, Duration lifetime) {
        return cookies.cookie(STATE_COOKIE, value, "/api/auth/oauth2", "Lax", lifetime);
    }
}
