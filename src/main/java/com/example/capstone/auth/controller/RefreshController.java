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
@Tag(name = "인증")
@RequestMapping("/api/auth")
public class RefreshController {
    private final RefreshTokenService tokens;
    private final AuthCookieService cookies;

    public RefreshController(RefreshTokenService tokens, AuthCookieService cookies) {
        this.tokens = tokens;
        this.cookies = cookies;
    }

    @Operation(summary = "액세스 토큰 갱신 / 재접속 복원",
            description = "Bearer 토큰은 필요 없습니다. FE에서 credentials: include로 호출하세요. 브라우저가 보내는 Origin은 CAPSTONE_APP_URL과 정확히 같아야 하고 CAPSTONE_REFRESH HttpOnly 쿠키가 필요합니다. 성공 시 새 액세스 토큰과 교체된 refresh 쿠키를 발급합니다. FE는 액세스 토큰을 메모리에 보관하고 같은 세션의 동시 refresh 요청을 조율하세요. 이미 사용한 refresh 토큰을 재사용하면 해당 세션 계열이 폐기됩니다. Swagger의 출처(예: BE 8080)와 앱 출처(예: FE 5173)가 다르면 Try it out은 403입니다. 브라우저의 Origin·Cookie는 입력으로 덮어쓸 수 없으므로 실제 앱 출처에서 쿠키 흐름을 확인하세요.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "처리 성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = TokenResponse.class))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: refresh 쿠키가 없거나 만료·폐기·재사용되었습니다. 쿠키를 지우며 다시 로그인해야 합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "UNAUTHENTICATED", value = "{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"로그인이 필요합니다.\"}}")})),
        @ApiResponse(responseCode = "403", description = "FORBIDDEN: Origin이 없거나 CAPSTONE_APP_URL과 다릅니다. 앱 출처와 credentials 설정을 확인하세요.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "FORBIDDEN", value = "{\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"허용된 앱에서 요청해 주세요.\"}}")}))
    })
    @PostMapping("/refresh")
    public TokenResponse refresh(@Parameter(hidden = true) @RequestHeader(value = "Origin", required = false) String origin,
            @Parameter(hidden = true) @CookieValue(value = AuthCookieService.REFRESH_COOKIE, required = false) String raw,
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

    @Operation(summary = "현재 기기 로그아웃",
            description = "FE에서 credentials: include로 호출합니다. Origin 조건은 refresh와 같습니다. 현재 refresh 세션 계열을 폐기하고 쿠키를 지우며 응답 본문은 없습니다. 쿠키가 없어도 올바른 Origin이면 204입니다. 다른 기기의 로그인은 유지됩니다. FE는 메모리의 액세스 토큰과 사용자 상태도 지워야 합니다. 이미 발급한 액세스 토큰은 만료 전까지 유효합니다. Swagger와 앱 출처가 다르면 403이므로 실제 앱에서 확인하세요.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "삭제/로그아웃 완료 (본문 없음)", content = @Content),
        @ApiResponse(responseCode = "403", description = "FORBIDDEN: Origin이 없거나 CAPSTONE_APP_URL과 다릅니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "FORBIDDEN", value = "{\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"허용된 앱에서 요청해 주세요.\"}}")}))
    })
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Parameter(hidden = true) @RequestHeader(value = "Origin", required = false) String origin,
            @Parameter(hidden = true) @CookieValue(value = AuthCookieService.REFRESH_COOKIE, required = false) String raw) {
        cookies.requireOrigin(origin);
        tokens.logout(raw);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clearRefresh()).build();
    }
}
