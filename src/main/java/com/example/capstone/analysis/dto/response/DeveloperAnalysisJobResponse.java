package com.example.capstone.analysis.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 개발자 대시보드의 분석 작업 상세. AI 요청·응답 원문, 결론·근거 문장은 넣지 않는다. */
public record DeveloperAnalysisJobResponse(UUID id, DeveloperAnalysisJobSummaryResponse.User user, UUID noteId, int noteVersion,
        String status, String lifecycleState, String failureCode, int attempt, Instant createdAt, Instant startedAt,
        Instant finishedAt, Long durationMs, String extractor, Instant deadline, List<Stage> stages, List<Input> inputs,
        Results results) {
    public record Stage(String stageKey, String operation, int attempts, long durationMs, boolean completed, Instant createdAt) {
    }

    public record Input(UUID noteId, Boolean factsCacheHit) {
    }

    public record Results(long total, long recommendable, Feedback feedback) {
    }

    public record Feedback(long understood, long known, long confused) {
    }
}
