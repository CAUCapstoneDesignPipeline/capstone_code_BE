package com.example.capstone.candidate.dto.request;

import java.util.Set;
import tools.jackson.databind.JsonNode;
import com.example.capstone.global.validation.TextValidation;

public record RecommendationFeedbackRequest(String action) {
    private static final Set<String> ACTIONS=Set.of("understood","known","confused");
    public static RecommendationFeedbackRequest parse(JsonNode value) {
        if(value==null || !value.isObject() || !value.path("action").isString() || !ACTIONS.contains(value.path("action").asString()))
            throw TextValidation.invalid("action","invalid","응답을 다시 선택하세요.");
        return new RecommendationFeedbackRequest(value.path("action").asString());
    }
}
