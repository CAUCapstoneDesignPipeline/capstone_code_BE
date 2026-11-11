package com.example.capstone.candidate.dto.response;

import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

/** 추천 카드. evidenceA는 작업을 요청한 노트의 근거, evidenceB는 다른 노트의 근거다. */
public record RecommendationResponse(UUID id,Evidence evidenceA,Evidence evidenceB,
        @Schema(nullable=true,description="외부 지식이 없는 결과는 null") ExternalKnowledge externalKnowledge,
        String conclusion,List<String> relatedKeywords) {
    public record Evidence(UUID noteId,String noteTitle,int noteVersion,int startOffset,int endOffset,String quote) { }
    public record ExternalKnowledge(String summary,String sourceUrl) { }
}
