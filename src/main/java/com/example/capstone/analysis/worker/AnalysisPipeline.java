package com.example.capstone.analysis.worker;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** External computation runs outside transactions; outputs are committed only after a final fence. */
public interface AnalysisPipeline {
    PipelineOutput run(Work work);
    default boolean configured() { return true; }
    default java.util.Set<String> requiredProfiles() { return java.util.Set.of(); }
    default String policyFingerprint() { return null; }
    record Work(UUID id, UUID userId, UUID noteId, UUID leaseToken, long graphRevision,
            Instant deadline, List<JsonNode> documents,String inputPolicy) { }
    record PipelineOutput(List<JsonNode> facts,List<JsonNode> graphItems,List<JsonNode> results) { }
}
