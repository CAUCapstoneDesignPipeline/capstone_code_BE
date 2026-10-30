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
public class GraphController {
    private final GraphService graph;
    public GraphController(GraphService graph) { this.graph=graph; }
    @GetMapping("/api/notes/{id}/graph") public ResponseEntity<JsonNode> note(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) { return response(graph.note(UUID.fromString(jwt.getSubject()),GraphService.id(id))); }
    @GetMapping("/api/graph") public ResponseEntity<JsonNode> graph(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="all") String scope,@RequestParam(required=false) String noteId,@RequestParam(required=false) String topicId,@RequestParam(defaultValue="true") boolean includeLost) {
        return response(graph.graph(UUID.fromString(jwt.getSubject()),scope,noteId,topicId,includeLost));
    }
    @GetMapping("/api/relations/{id}") public ResponseEntity<JsonNode> relation(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) { return response(graph.relation(UUID.fromString(jwt.getSubject()),GraphService.id(id))); }
    @GetMapping("/api/relation-types") public ResponseEntity<Map<String,List<JsonNode>>> types() { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("types",graph.types())); }
    private ResponseEntity<JsonNode> response(JsonNode body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
