package com.example.capstone.analysis.dto.response;

import java.time.Instant;
import java.util.UUID;

public record AnalysisJobResponse(UUID id, UUID noteId, int noteVersion, String status,
        Instant createdAt, Instant startedAt, Instant finishedAt,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String lifecycleState,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Long inputRevision,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String failureCode) {
    public static AnalysisJobResponse from(com.example.capstone.analysis.domain.AnalysisJob job) {
        return new AnalysisJobResponse(job.getId(),job.getNoteId(),job.getNoteVersion(),job.getStatus(),job.getCreatedAt(),job.getStartedAt(),job.getFinishedAt(),job.getLifecycleState(),job.getInputRevision(),job.getFailureCode());
    }
    public static AnalysisJobResponse from(tools.jackson.databind.JsonNode job) {
        return new AnalysisJobResponse(UUID.fromString(job.path("id").asString()),UUID.fromString(job.path("note_id").asString()),job.path("note_version").asInt(),job.path("status").asString(),
                instant(job,"created_at"),instant(job,"started_at"),instant(job,"finished_at"),text(job,"lifecycle_state"),job.path("input_revision").isNull()?null:job.path("input_revision").asLong(),text(job,"failure_code"));
    }
    private static Instant instant(tools.jackson.databind.JsonNode node,String key) { return node.path(key).isNull()?null:Instant.parse(node.path(key).asString()); }
    private static String text(tools.jackson.databind.JsonNode node,String key) { return node.path(key).isNull()?null:node.path(key).asString(); }
}
