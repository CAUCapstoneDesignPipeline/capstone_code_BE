package com.example.capstone.topic.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

public record TopicOrderRequest(
        @Schema(description = "순서를 바꿀 부모 주제 UUID. 생략하거나 null이면 최상위 주제의 순서를 바꿉니다.", nullable = true)
        UUID parentId,
        @Schema(description = "그 부모의 바로 아래 주제 ID 전체를 원하는 순서로 한 번씩 보냅니다. 하위 주제가 없을 때만 []가 성공합니다. 중복/null 금지.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<UUID> topicIds) { }
