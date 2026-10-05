package com.example.capstone.topic.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
public record TopicNameRequest(
        @Schema(description = "필수. NFC 정규화·trim 후 1~50 코드 포인트. / 금지, 본인 주제 내 중복 금지(대소문자 구분).", requiredMode = Schema.RequiredMode.REQUIRED, example = "데이터베이스", minLength = 1, maxLength = 50)
        String name) { }
