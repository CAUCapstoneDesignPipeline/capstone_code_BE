package com.example.capstone.analysis.dto.response;

import java.time.Instant;
import java.util.UUID;

public record AnalysisJobResponse(UUID id, UUID noteId, int noteVersion, String status,
        Instant createdAt, Instant startedAt, Instant finishedAt) {
}
