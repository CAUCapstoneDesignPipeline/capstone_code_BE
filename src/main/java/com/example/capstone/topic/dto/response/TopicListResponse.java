package com.example.capstone.topic.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record TopicListResponse(
        @Schema(description = "본인 주제 전체(모든 깊이). parentId로 트리를 만들고, 같은 부모 안에서는 이 배열 순서(sortOrder 순)를 따르세요.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<TopicResponse> topics,
        @Schema(description = "topicId가 null인 본인 노트 개수", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
        long unassignedNoteCount) { }
