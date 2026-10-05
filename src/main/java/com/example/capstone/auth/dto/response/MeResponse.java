package com.example.capstone.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

public record MeResponse(
        @Schema(description = "사용자 UUID. provider의 sub나 이메일을 사용자 ID로 사용하지 않습니다.", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(description = "사용자 이메일. 제공자 정보에 따라 null일 수 있습니다.", types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED, example = "dev@example.com")
        String email,
        @Schema(description = "화면에 표시할 사용자 이름", requiredMode = Schema.RequiredMode.REQUIRED, example = "개발자")
        String displayName,
        @Schema(description = "연결된 신원 제공자 ID. dev와 google 신원은 이메일이 같아도 자동 연결하지 않습니다.", requiredMode = Schema.RequiredMode.REQUIRED, example = "[\"dev\"]")
        List<String> providers) {
}
