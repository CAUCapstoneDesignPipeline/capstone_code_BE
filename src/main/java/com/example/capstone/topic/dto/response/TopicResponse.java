package com.example.capstone.topic.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record TopicResponse(
        @Schema(description = "주제 UUID", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(description = "정규화된 주제 이름", requiredMode = Schema.RequiredMode.REQUIRED, example = "데이터베이스")
        String name,
        @Schema(description = "주제 표시 순서. 0부터 시작합니다.", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
        int sortOrder,
        @Schema(description = "이 주제에 속한 본인 노트 개수", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
        long noteCount,
        @Schema(description = "생성 시각 (RFC 3339 UTC)", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-10-02T00:00:00Z")
        Instant createdAt) { }
