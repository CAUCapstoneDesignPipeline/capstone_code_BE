package com.example.capstone.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import com.example.capstone.global.response.ErrorResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.capstone.auth.config.AuthEnvironment;
import com.example.capstone.auth.config.AuthProperties;
import com.example.capstone.auth.dto.response.AuthProviderListResponse;

@RestController
@Tag(name = "인증")
public class AuthProviderController {
    private final Environment environment;
    private final AuthProperties properties;
    public AuthProviderController(Environment environment, AuthProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }
    @Operation(summary = "로그인 수단 조회",
            description = "인증 없이 호출합니다. providers에 포함된 소셜 로그인 버튼만 표시하세요. 빈 배열은 소셜 로그인이 비활성화된 상태이며, devTokenEnabled가 true일 때만 개발용 로그인 버튼을 표시합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "처리 성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthProviderListResponse.class)))
    })
    @GetMapping("/api/auth/providers")
    public AuthProviderListResponse providers() {
        return new AuthProviderListResponse(properties.google() != null && properties.google().enabled()
                ? List.of(new AuthProviderListResponse.Provider("google", "Google")) : List.of(), AuthEnvironment.isDevelopment(environment)
                && environment.getProperty("capstone.auth.dev-token.enabled", Boolean.class, false));
    }
}
