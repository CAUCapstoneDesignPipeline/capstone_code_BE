package com.example.capstone.topic.dto.request;

import java.util.List;
import java.util.UUID;

public record TopicOrderRequest(List<UUID> topicIds) { }
