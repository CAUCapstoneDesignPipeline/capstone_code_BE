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
import com.example.capstone.concept.service.TopicGraphService;
import com.example.capstone.concept.service.GraphSearchService;
import com.example.capstone.concept.dto.response.GraphSearchResponse;
import com.example.capstone.concept.dto.response.TopicGraphResponse;

@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name="그래프")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bearerAuth")
public class GraphController {
    private final GraphService graph;
    private final TopicGraphService topicGraph;
    private final GraphSearchService graphSearch;
    public GraphController(GraphService graph,TopicGraphService topicGraph,GraphSearchService graphSearch) { this.graph=graph;this.topicGraph=topicGraph;this.graphSearch=graphSearch; }
    @io.swagger.v3.oas.annotations.Operation(summary="노트 개념과 관계",description="소유 노트의 최근 분석, 개념, 원문 근거 관계를 조회합니다.")
    @GetMapping("/api/notes/{id}/graph") public ResponseEntity<JsonNode> note(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) { return response(graph.note(UUID.fromString(jwt.getSubject()),GraphService.id(id))); }
    @io.swagger.v3.oas.annotations.Operation(summary="페이지 그래프 보기",description="노드는 페이지(노트), 엣지는 키워드를 하나 이상 공유하는 두 페이지 사이의 선 1개입니다. scope=topic이면 topicId(미분류는 none)가 필요합니다. 생성된 지식은 넣지 않습니다.")
    @GetMapping("/api/graph") public ResponseEntity<JsonNode> graph(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="all") String scope,@RequestParam(required=false) String topicId) {
        return response(graph.pages(UUID.fromString(jwt.getSubject()),scope,topicId));
    }
    @io.swagger.v3.oas.annotations.Operation(summary="주제 그래프 최상위 단계",description="Galaxy 그래프의 처음 화면입니다. 최상위 주제 노드와 미분류 요약(unassigned)을 주며 keywords는 비어 있습니다. 선은 주제 사이로 합친 관계입니다.")
    @GetMapping("/api/graph/topics") public ResponseEntity<TopicGraphResponse> topics(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(topicGraph.top(UUID.fromString(jwt.getSubject())));
    }
    @io.swagger.v3.oas.annotations.Operation(summary="주제 그래프 한 단계",description="topicId 주제의 바로 아래 하위 주제와 그 주제에 바로 속한 키워드를 줍니다. 하위 주제 안 키워드는 넣지 않습니다. topicId=none이면 미분류 키워드 단계입니다. 타인·없는 주제는 404, UUID 형식이 아니면 400입니다.")
    @GetMapping("/api/graph/topics/{topicId}") public ResponseEntity<TopicGraphResponse> topic(@AuthenticationPrincipal Jwt jwt,@PathVariable String topicId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(topicGraph.topic(UUID.fromString(jwt.getSubject()),topicId));
    }
    @io.swagger.v3.oas.annotations.Operation(summary="Galaxy 그래프 검색",description="주제 이름과 키워드에서 q를 부분 일치(대소문자 무시)로 찾습니다. 노트는 찾지 않습니다. 주제 결과가 먼저 오고, 같은 종류 안에서는 정확히 일치·앞부분 일치·이름 순입니다. 각 결과의 path를 따라 GET /graph/topics/{topicId}를 차례로 부르면 그 노드가 있는 단계에 닿습니다. q가 비면 400, limit은 1~50(기본 20)입니다.")
    @GetMapping("/api/graph/search") public ResponseEntity<GraphSearchResponse> search(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) String q,@RequestParam(required=false) Integer limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(graphSearch.search(UUID.fromString(jwt.getSubject()),q,limit));
    }
    @GetMapping("/api/relations/{id}") public ResponseEntity<JsonNode> relation(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) { return response(graph.relation(UUID.fromString(jwt.getSubject()),GraphService.id(id))); }
    @GetMapping("/api/relation-types") public ResponseEntity<Map<String,List<JsonNode>>> types() { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("types",graph.types())); }
    private ResponseEntity<JsonNode> response(JsonNode body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
