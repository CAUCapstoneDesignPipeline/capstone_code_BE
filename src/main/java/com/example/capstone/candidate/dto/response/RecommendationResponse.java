package com.example.capstone.candidate.dto.response;

import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 추천 카드. evidenceA는 작업을 요청한 노트의 근거, evidenceB는 다른 노트의 근거다.
 * id는 후보 지문으로 정해져 재분석해도 유지되고, 결론·근거가 바뀌면 revision이 오른다.
 */
public record RecommendationResponse(UUID id,@Schema(minimum="1") int revision,
        @Schema(allowableValues={"current","stale","evidence_lost"},description="stale·evidence_lost이면 모든 답 버튼을 막는다. 답하면 409 RECOMMENDATION_STALE") String freshness,
        Evidence evidenceA,Evidence evidenceB,
        @Schema(nullable=true,description="외부 지식이 없는 결과는 null") ExternalKnowledge externalKnowledge,
        String conclusion,List<String> relatedKeywords) {
    public record Evidence(UUID noteId,String noteTitle,int noteVersion,int startOffset,int endOffset,String quote) { }
    public record ExternalKnowledge(String summary,String sourceUrl) { }
}
