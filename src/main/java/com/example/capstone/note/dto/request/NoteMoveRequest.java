package com.example.capstone.note.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

@Schema(example = "{\"topicId\":null}")
public record NoteMoveRequest(
        @JsonProperty(required = true)
        @Schema(description = "필수 필드. 본인 주제 UUID 또는 null(미분류). 필드 자체를 생략하면 400입니다.", types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED)
        UUID topicId) { }
