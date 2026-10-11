package com.example.capstone.topic.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

public record TopicOrderRequest(
        @Schema(description = "본인 주제 전체 ID를 원하는 순서로 한 번씩 보냅니다. 현재 주제가 없을 때만 []가 성공합니다. 중복/null 금지.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<UUID> topicIds) { }
