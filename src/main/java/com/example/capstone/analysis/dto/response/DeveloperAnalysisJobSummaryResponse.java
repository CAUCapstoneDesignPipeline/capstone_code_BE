package com.example.capstone.analysis.dto.response;

import java.time.Instant;
import java.util.UUID;

/** 개발자 대시보드의 분석 작업 한 줄. 다른 사용자의 노트는 id만 준다. */
public record DeveloperAnalysisJobSummaryResponse(UUID id, User user, UUID noteId, int noteVersion, String status,
        String lifecycleState, String failureCode, int attempt, Instant createdAt, Instant startedAt, Instant finishedAt,
        Long durationMs) {
    public record User(UUID id, String email) {
    }
}
