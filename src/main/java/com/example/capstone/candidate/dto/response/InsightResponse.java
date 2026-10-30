package com.example.capstone.candidate.dto.response;

import java.util.List;
import java.util.UUID;

public record InsightResponse(UUID id,int revision,String conclusion,List<String> conditions,List<String> trace,
        List<Evidence> evidence,String derivationStatus,String soundness,String reviewStatus,String freshness,List<UUID> noveltyNoteIds) {
    public record Evidence(UUID noteId,int noteVersion,long inputRevision,String bodySha256,int startOffset,int endOffset,String quote) { }
}
