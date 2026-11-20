package com.example.capstone.topic.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record TopicMoveRequest(
        @Schema(description = "새 부모 주제 UUID. null이면 최상위로 옮깁니다. 자기 자신이나 자기 하위 주제는 지정할 수 없습니다.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        UUID parentId) { }
