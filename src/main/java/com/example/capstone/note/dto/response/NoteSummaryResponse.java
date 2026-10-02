package com.example.capstone.note.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record NoteSummaryResponse(
        @Schema(description = "노트 UUID. 단건 원문 조회에 사용합니다.", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(description = "주제 UUID 또는 null(미분류). 필드는 항상 포함됩니다.", types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED)
        UUID topicId,
        @Schema(description = "노트 제목", requiredMode = Schema.RequiredMode.REQUIRED, example = "트랜잭션")
        String title,
        @Schema(description = "최대 80 코드 포인트의 본문 문맥. 전체 body가 아니며 Markdown·HTML을 실행하지 않고 텍스트로 표시합니다.", requiredMode = Schema.RequiredMode.REQUIRED, example = "트랜잭션은 여러 작업을 하나의 단위로 처리합니다.", maxLength = 80)
        String snippet,
        @Schema(description = "목록 조회 시점의 version. 편집기 열기는 단건 조회를 사용하세요.", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
        int version,
        @Schema(description = "최근 수정 시각 (RFC 3339 UTC)", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-10-02T00:00:00Z")
        Instant updatedAt) { }
