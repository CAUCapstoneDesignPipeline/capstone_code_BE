package com.example.capstone.concept.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import com.example.capstone.concept.service.GraphService;

@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name="그래프")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bearerAuth")
public class GraphController {
    private final GraphService graph;
    public GraphController(GraphService graph) { this.graph=graph; }
    @io.swagger.v3.oas.annotations.Operation(summary="노트 개념과 관계",description="소유 노트의 최근 분석, 개념, 원문 근거 관계를 조회합니다.")
    @GetMapping("/api/notes/{id}/graph") public ResponseEntity<JsonNode> note(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) { return response(graph.note(UUID.fromString(jwt.getSubject()),GraphService.id(id))); }
    @io.swagger.v3.oas.annotations.Operation(summary="페이지 그래프 보기",description="노드는 페이지(노트), 엣지는 키워드를 하나 이상 공유하는 두 페이지 사이의 선 1개입니다. scope=topic이면 topicId(미분류는 none)가 필요합니다. 생성된 지식은 넣지 않습니다.")
    @GetMapping("/api/graph") public ResponseEntity<JsonNode> graph(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="all") String scope,@RequestParam(required=false) String topicId) {
        return response(graph.pages(UUID.fromString(jwt.getSubject()),scope,topicId));
    }
    @GetMapping("/api/relations/{id}") public ResponseEntity<JsonNode> relation(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) { return response(graph.relation(UUID.fromString(jwt.getSubject()),GraphService.id(id))); }
    @GetMapping("/api/relation-types") public ResponseEntity<Map<String,List<JsonNode>>> types() { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("types",graph.types())); }
    private ResponseEntity<JsonNode> response(JsonNode body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
