package com.example.capstone.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import com.example.capstone.global.response.ErrorResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

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
@Tag(name = "인증")
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
    @Operation(summary = "개발용 액세스 토큰 발급",
            description = "local 또는 test 단독 프로필에서 개발용 발급을 활성화한 경우에만 존재합니다. 운영·비활성 환경에서는 404이며 Swagger에도 나타나지 않습니다. 본문 생략 또는 {}는 기본 개발 사용자입니다. 같은 email은 같은 dev 신원을 재사용합니다. 응답 accessToken을 상단 Authorize에 입력하면 주제·노트 API를 실행할 수 있습니다. issueRefreshCookie=true는 HttpOnly refresh 쿠키도 발급합니다. Google 로그인 흐름 검증을 대체하지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "처리 성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = TokenResponse.class))),
        @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: 요청 형식·필드 값을 확인하세요. error.details.fields가 있으면 field와 reason으로 입력 오류를 표시합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "VALIDATION_FAILED", value = "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"입력값을 확인해 주세요.\",\"details\":{\"fields\":[{\"field\":\"displayName\",\"reason\":\"invalid\"}]}}}")}))
    })
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
