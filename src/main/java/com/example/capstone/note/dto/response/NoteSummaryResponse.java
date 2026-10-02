package com.example.capstone.note.dto.response;

import java.time.Instant;
import java.util.UUID;

public record NoteSummaryResponse(UUID id,UUID topicId,String title,String snippet,int version,Instant updatedAt) { }
