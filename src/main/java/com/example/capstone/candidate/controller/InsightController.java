package com.example.capstone.candidate.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.example.capstone.candidate.service.InsightService;
import com.example.capstone.candidate.dto.request.ReviewEventRequest;
import com.example.capstone.candidate.dto.response.InsightResponse;
import com.example.capstone.candidate.dto.response.InsightListResponse;
import com.example.capstone.candidate.dto.response.ReviewEventResponse;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;

@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name="발견")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bearerAuth")
public class InsightController {
    private final InsightService service;
    public InsightController(InsightService service) { this.service=service; }
    @GetMapping("/api/candidates") public ResponseEntity<InsightListResponse> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="recommendations") String view,@RequestParam(defaultValue="5") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(UUID.fromString(jwt.getSubject()),view,limit));
    }
    @GetMapping("/api/candidates/{id}") public ResponseEntity<InsightResponse> get(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(UUID.fromString(jwt.getSubject()),uuid(id)));
    }
    @PostMapping("/api/candidates/{id}/review") public ResponseEntity<ReviewEventResponse> review(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestBody tools.jackson.databind.JsonNode request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.review(UUID.fromString(jwt.getSubject()),uuid(id),ReviewEventRequest.parse(request)));
    }
    @GetMapping("/api/reviews") public ResponseEntity<Map<String,List<ReviewEventResponse>>> reviews(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("reviews",service.reviews(UUID.fromString(jwt.getSubject()))));
    }
    static UUID uuid(String value) {
        try { UUID id=UUID.fromString(value);if(id.toString().equalsIgnoreCase(value)) return id; } catch(IllegalArgumentException invalid) { }
        throw new ApiException(ErrorCode.VALIDATION_FAILED,"요청 값이 올바르지 않습니다.");
    }
}
