package com.example.capstone.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
public record TokenResponse(
        @Schema(description = "30분 유효한 JWT. FE 메모리에 보관하고 Authorization: Bearer <값>으로 전송합니다. Swagger Authorize에는 이 값만 입력하세요.", requiredMode = Schema.RequiredMode.REQUIRED, example = "<accessToken>")
        String accessToken,
        @Schema(description = "인증 헤더의 접두사", requiredMode = Schema.RequiredMode.REQUIRED, example = "Bearer")
        String tokenType,
        @Schema(description = "액세스 토큰의 유효 기간(초)", requiredMode = Schema.RequiredMode.REQUIRED, example = "1800")
        long expiresIn,
        @Schema(description = "현재 로그인 사용자", requiredMode = Schema.RequiredMode.REQUIRED)
        MeResponse user) {
    @Override
    public String toString() {
        return "TokenResponse[accessToken=<redacted>, tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
    }
}
