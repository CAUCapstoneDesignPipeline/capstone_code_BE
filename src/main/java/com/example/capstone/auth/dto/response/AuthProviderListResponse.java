package com.example.capstone.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record AuthProviderListResponse(
        @Schema(description = "활성화된 소셜 로그인 수단. 비활성 환경은 빈 배열입니다.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<Provider> providers,
        @Schema(description = "true일 때만 개발용 로그인 UI를 표시하세요.", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
        boolean devTokenEnabled) {
    public record Provider(
        @Schema(description = "OAuth 시작 경로의 provider 값", requiredMode = Schema.RequiredMode.REQUIRED, example = "google")
        String id,
        @Schema(description = "로그인 버튼 표시 이름", requiredMode = Schema.RequiredMode.REQUIRED, example = "Google")
        String name) { }
}
