package com.example.capstone.concept.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "주제 그래프(Galaxy) 한 단계. 최상위면 최상위 주제만, 주제 안이면 하위 주제와 그 주제에 바로 속한 키워드를 줍니다.")
public record TopicGraphResponse(
        @Schema(description = "이 단계의 하위 주제 노드. 같은 부모 안의 sortOrder 순입니다.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<TopicNode> topics,
        @Schema(description = "이 단계에 바로 속한 키워드 노드. 최상위에서는 항상 빈 배열입니다.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<KeywordNode> keywords,
        @Schema(description = "이 단계에 보이는 노드 사이의 무방향 선. 하위 주제 안쪽 키워드와 이어진 관계는 그 하위 주제 노드로 합쳐 그립니다.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<Edge> edges,
        @Schema(description = "최상위 응답에만 있는 미분류 노드 정보. 주제 안 단계와 미분류 단계에서는 null입니다.", nullable = true)
        Unassigned unassigned) {

    public record TopicNode(
            @Schema(description = "주제 UUID", requiredMode = Schema.RequiredMode.REQUIRED) String id,
            @Schema(description = "주제 이름", requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(description = "이 주제와 모든 하위 주제에 속한 키워드 수(노드 크기용)", requiredMode = Schema.RequiredMode.REQUIRED) int keywordCount,
            @Schema(description = "하위 주제가 있는지", requiredMode = Schema.RequiredMode.REQUIRED) boolean hasChildren,
            @Schema(description = "바로 아래 하위 주제 수", requiredMode = Schema.RequiredMode.REQUIRED) int childTopicCount,
            @Schema(description = "이 주제나 하위 주제에 이해함으로 들어온 지식(origin=generated)이 있는지", requiredMode = Schema.RequiredMode.REQUIRED) boolean hasRecommended) { }

    public record KeywordNode(
            @Schema(description = "키워드 ID. 같은 사용자에게서 같은 키워드면 요청마다 같은 값입니다.", requiredMode = Schema.RequiredMode.REQUIRED) String id,
            @Schema(description = "대표 표기", requiredMode = Schema.RequiredMode.REQUIRED) String label,
            @Schema(description = "이 키워드로 합쳐진 개념(설명) 수(노드 크기용)", requiredMode = Schema.RequiredMode.REQUIRED) int conceptCount,
            @Schema(description = "extracted: 노트 본문에서 뽑은 지식, generated: 이해함으로 들어온 지식", requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"extracted","generated"}) String origin,
            @Schema(description = "바로 속한 주제 UUID. 미분류면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String topicId,
            @Schema(description = "이 키워드가 나온 노트(제목 순). 다른 주제의 노트도 포함합니다.", requiredMode = Schema.RequiredMode.REQUIRED) List<Document> documents) { }

    public record Document(
            @Schema(description = "노트 UUID", requiredMode = Schema.RequiredMode.REQUIRED) String noteId,
            @Schema(description = "노트 제목", requiredMode = Schema.RequiredMode.REQUIRED) String title) { }

    public record Edge(
            @Schema(description = "주제 또는 키워드 노드 ID", requiredMode = Schema.RequiredMode.REQUIRED) String sourceId,
            @Schema(description = "주제 또는 키워드 노드 ID", requiredMode = Schema.RequiredMode.REQUIRED) String targetId) { }

    public record Unassigned(
            @Schema(description = "미분류에 속한 키워드 수. 0이면 FE는 미분류 노드를 그리지 않습니다.", requiredMode = Schema.RequiredMode.REQUIRED) int keywordCount,
            @Schema(description = "미분류에 이해함으로 들어온 지식이 있는지", requiredMode = Schema.RequiredMode.REQUIRED) boolean hasRecommended) { }
}
