package com.example.capstone.candidate.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

public record ReviewEventRequest(@JsonProperty(required=true) @NotNull Integer expectedRevision,
        @JsonProperty(required=true) @NotNull String action,@JsonProperty(required=true) String reason,
        @JsonProperty(required=true) String learning,@JsonProperty(required=true) String editedConclusion,
        @JsonProperty(required=true) @NotNull String idempotencyKey) {
    public static ReviewEventRequest parse(tools.jackson.databind.JsonNode value) {
        var expected=java.util.Set.of("expectedRevision","action","reason","learning","editedConclusion","idempotencyKey");
        var keys=new java.util.HashSet<String>();value.properties().forEach(e->keys.add(e.getKey()));
        if(!value.isObject() || !keys.equals(expected) || !value.path("expectedRevision").isIntegralNumber() || !value.path("expectedRevision").canConvertToInt()
                || !value.path("action").isString() || !value.path("idempotencyKey").isString()) throw invalid();
        for(String key:new String[]{"reason","learning","editedConclusion"}) if(!value.path(key).isString() && !value.path(key).isNull()) throw invalid();
        return new ReviewEventRequest(value.path("expectedRevision").asInt(),value.path("action").asString(),text(value,"reason"),text(value,"learning"),text(value,"editedConclusion"),value.path("idempotencyKey").asString());
    }
    private static String text(tools.jackson.databind.JsonNode n,String key) { return n.path(key).isNull()?null:n.path(key).asString(); }
    private static com.example.capstone.global.exception.ApiException invalid() { return new com.example.capstone.global.exception.ApiException(com.example.capstone.global.exception.ErrorCode.VALIDATION_FAILED,"요청 값이 올바르지 않습니다."); }
}
