package com.example.capstone.topic.dto.response;

import java.time.Instant;
import java.util.UUID;

public record TopicResponse(UUID id,String name,int sortOrder,long noteCount,Instant createdAt) { }
