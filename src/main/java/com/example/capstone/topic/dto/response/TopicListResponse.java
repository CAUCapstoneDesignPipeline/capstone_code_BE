package com.example.capstone.topic.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record TopicListResponse(
        @Schema(description = "sortOrder 순으로 정렬된 본인 주제 목록", requiredMode = Schema.RequiredMode.REQUIRED)
        List<TopicResponse> topics,
        @Schema(description = "topicId가 null인 본인 노트 개수", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
        long unassignedNoteCount) { }
