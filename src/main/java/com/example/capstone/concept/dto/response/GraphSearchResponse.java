package com.example.capstone.concept.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Galaxy 그래프 검색 결과. 주제가 먼저, 그다음 키워드입니다.")
public record GraphSearchResponse(
        @Schema(description = "검색 결과(최대 limit개)", requiredMode = Schema.RequiredMode.REQUIRED)
        List<Result> results) {

    public record Result(
            @Schema(description = "topic 또는 keyword", requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"topic","keyword"}) String kind,
            @Schema(description = "주제 UUID 또는 키워드 ID(GET /graph/topics/{topicId}의 키워드 id와 같음)", requiredMode = Schema.RequiredMode.REQUIRED) String id,
            @Schema(description = "주제 이름 또는 키워드 대표 표기", requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(description = "주제는 항상 extracted, 키워드는 이해함으로 들어온 지식이면 generated", requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"extracted","generated"}) String origin,
            @Schema(description = "최상위 주제부터 그 노드가 속한 주제까지. 주제 결과는 자기 자신을 뺀 부모들(최상위 주제면 빈 배열), 미분류 키워드는 [{id:none,name:미분류}]입니다.", requiredMode = Schema.RequiredMode.REQUIRED) List<PathItem> path) { }

    public record PathItem(
            @Schema(description = "주제 UUID. 미분류는 none", requiredMode = Schema.RequiredMode.REQUIRED) String id,
            @Schema(description = "주제 이름", requiredMode = Schema.RequiredMode.REQUIRED) String name) { }
}
