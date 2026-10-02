package com.example.capstone.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import com.example.capstone.global.response.ErrorResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.capstone.auth.dto.response.MeResponse;
import com.example.capstone.auth.service.CurrentUserService;

@RestController
@Tag(name = "인증")
@RequestMapping("/api/auth")
@SecurityRequirement(name = "bearerAuth")
public class AuthController {

    private final CurrentUserService currentUsers;

    public AuthController(CurrentUserService currentUsers) {
        this.currentUsers = currentUsers;
    }

    @Operation(summary = "현재 사용자 조회",
            description = "Authorize에 accessToken 값만 입력합니다. FE는 로그인·refresh 응답의 사용자 정보와 이 응답을 사용합니다. email은 null일 수 있고, providers는 연결된 신원 제공자 ID 목록입니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "처리 성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = MeResponse.class))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 액세스 토큰이 없거나 만료되었습니다. refresh 성공 후 새 토큰으로 재시도하고, refresh도 401이면 로그인 화면으로 이동합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "UNAUTHENTICATED", value = "{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"로그인이 필요합니다.\"}}")}))
    })
    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        return currentUsers.getMe(UUID.fromString(jwt.getSubject()));
    }
}
