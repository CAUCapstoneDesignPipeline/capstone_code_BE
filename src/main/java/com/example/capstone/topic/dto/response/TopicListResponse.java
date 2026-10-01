package com.example.capstone.topic.dto.response;

import java.util.List;

public record TopicListResponse(List<TopicResponse> topics,long unassignedNoteCount) { }
