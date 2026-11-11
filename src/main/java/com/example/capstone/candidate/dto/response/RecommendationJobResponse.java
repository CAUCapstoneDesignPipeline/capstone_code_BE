package com.example.capstone.candidate.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

/** 추천 작업. outcome은 completed일 때만 채운다(RECOMMENDED, NO_RECOMMENDATIONS, RECOMMENDATION_FAILED). */
public record RecommendationJobResponse(UUID id,UUID noteId,
        @Schema(allowableValues={"pending","completed"}) String status,
        @Schema(nullable=true,allowableValues={"RECOMMENDED","NO_RECOMMENDATIONS","RECOMMENDATION_FAILED"}) String outcome,
        List<RecommendationResponse> recommendations,Instant createdAt,@Schema(nullable=true) Instant completedAt) { }
