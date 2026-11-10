package com.example.capstone.candidate.dto.response;
import java.time.Instant;
import java.util.UUID;
public record ReviewEventResponse(UUID id,UUID candidateId,int revision,String action,String reason,String learning,String editedConclusion,Instant createdAt) { }
