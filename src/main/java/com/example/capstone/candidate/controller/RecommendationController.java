package com.example.capstone.candidate.controller;

import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import com.example.capstone.candidate.dto.request.RecommendationFeedbackRequest;
import com.example.capstone.candidate.dto.response.RecommendationJobResponse;
import com.example.capstone.candidate.service.RecommendationService;

@RestController
@Tag(name="추천")
@SecurityRequirement(name="bearerAuth")
public class RecommendationController {
    private final RecommendationService service;
    public RecommendationController(RecommendationService service) { this.service=service; }
    @Operation(summary="추천 작업 요청",description="노트의 현재 내용으로 추천 작업을 만들거나 끝나지 않은 같은 노트의 작업을 202로 줍니다. AI가 꺼져 있으면 503 AI_UNAVAILABLE입니다.")
    @PostMapping("/api/notes/{noteId}/recommendation-jobs")
    public ResponseEntity<RecommendationJobResponse> request(@AuthenticationPrincipal Jwt jwt,@PathVariable String noteId) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(service.request(user(jwt),InsightController.uuid(noteId)));
    }
    @Operation(summary="추천 작업 조회",description="pending이면 만드는 중, completed면 끝났습니다. completed의 outcome은 RECOMMENDED, NO_RECOMMENDATIONS, RECOMMENDATION_FAILED 중 하나입니다. 추천은 최대 5개입니다.")
    @GetMapping("/api/recommendation-jobs/{jobId}")
    public ResponseEntity<RecommendationJobResponse> get(@AuthenticationPrincipal Jwt jwt,@PathVariable String jobId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(user(jwt),InsightController.uuid(jobId)));
    }
    @Operation(summary="추천 피드백",description="understood(이해함)는 시스템 그래프에 채택으로, known(알고 있던 내용)은 거절로 기록합니다. confused(이해가 안 됨)는 그래프에 반영하지 않습니다. 노트 본문은 바꾸지 않습니다.")
    @PostMapping("/api/recommendations/{recommendationId}/feedback")
    public ResponseEntity<Void> feedback(@AuthenticationPrincipal Jwt jwt,@PathVariable String recommendationId,@RequestBody(required=false) JsonNode body) {
        service.feedback(user(jwt),InsightController.uuid(recommendationId),RecommendationFeedbackRequest.parse(body));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    private static UUID user(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
