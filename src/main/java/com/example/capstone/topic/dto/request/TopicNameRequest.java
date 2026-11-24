package com.example.capstone.topic.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record TopicNameRequest(
        @Schema(description = "필수. NFC 정규화·trim 후 1~50 코드 포인트. / 금지, 같은 부모 안에서 중복 금지(대소문자 구분).", requiredMode = Schema.RequiredMode.REQUIRED, example = "데이터베이스", minLength = 1, maxLength = 50)
        String name,
        @Schema(description = "생성 시에만 사용. 부모 주제 UUID이며 생략하거나 null이면 최상위에 만듭니다. 이름 변경에서는 무시합니다.", nullable = true)
        UUID parentId) {
    public TopicNameRequest(String name) { this(name,null); }
}
