package com.example.capstone.note.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;
import com.example.capstone.note.domain.Note;

public record NoteResponse(
        @Schema(description = "노트 UUID", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(description = "주제 UUID. null이면 미분류이며 이 필드는 항상 응답에 포함됩니다.", types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED)
        UUID topicId,
        @Schema(description = "정규화된 노트 제목", requiredMode = Schema.RequiredMode.REQUIRED, example = "트랜잭션")
        String title,
        @Schema(description = "편집용 전체 원문. Markdown·HTML 여부와 관계없이 입력 원문입니다.", requiredMode = Schema.RequiredMode.REQUIRED, example = "트랜잭션은 여러 작업을 하나의 단위로 처리합니다.")
        String body,
        @Schema(description = "낙관적 잠금 버전. 다음 저장 요청에 그대로 사용하세요.", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
        int version,
        @Schema(description = "생성 시각 (RFC 3339 UTC)", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-10-02T00:00:00Z")
        Instant createdAt,
        @Schema(description = "최근 수정 시각 (RFC 3339 UTC)", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-10-02T00:00:00Z")
        Instant updatedAt) {
    public static NoteResponse from(Note note) {
        return new NoteResponse(note.getId(),note.getTopicId(),note.getTitle(),note.getBody(),note.getVersion(),note.getCreatedAt(),note.getUpdatedAt());
    }
}
