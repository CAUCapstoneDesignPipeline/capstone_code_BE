package com.example.capstone.topic.controller;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import com.example.capstone.topic.dto.request.TopicOrderRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.example.capstone.topic.dto.request.TopicNameRequest;
import com.example.capstone.topic.dto.response.TopicResponse;
import com.example.capstone.topic.dto.response.TopicListResponse;
import com.example.capstone.topic.service.TopicService;

@RestController
public class TopicController {
    private final TopicService topics;
    public TopicController(TopicService topics) { this.topics=topics; }
    @GetMapping("/api/topics")
    public TopicListResponse list(@AuthenticationPrincipal Jwt jwt) { return topics.list(UUID.fromString(jwt.getSubject())); }
    @PutMapping("/api/topics/order")
    public TopicListResponse reorder(@AuthenticationPrincipal Jwt jwt,@RequestBody TopicOrderRequest request) {
        return topics.reorder(UUID.fromString(jwt.getSubject()),request.topicIds());
    }
    @PostMapping("/api/topics")
    public ResponseEntity<TopicResponse> create(@AuthenticationPrincipal Jwt jwt,@RequestBody TopicNameRequest request) {
        return ResponseEntity.status(201).body(topics.create(UUID.fromString(jwt.getSubject()),request.name()));
    }
    @PatchMapping("/api/topics/{id}")
    public TopicResponse rename(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody TopicNameRequest request) {
        return topics.rename(UUID.fromString(jwt.getSubject()),id,request.name());
    }
}
