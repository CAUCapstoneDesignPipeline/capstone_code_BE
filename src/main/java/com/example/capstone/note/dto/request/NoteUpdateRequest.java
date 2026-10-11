package com.example.capstone.note.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record NoteUpdateRequest(
        @Schema(description = "필수. NFC 정규화·trim 후 1~200 코드 포인트. / 금지, 같은 주제 내 중복 금지.", requiredMode = Schema.RequiredMode.REQUIRED, example = "트랜잭션", minLength = 1, maxLength = 200)
        String title,
        @NotNull @Schema(description = "필수. 빈 문자열 허용, null 금지. NFC 정규화, 공백 유지, 최대 1,000,000 코드 포인트.", requiredMode = Schema.RequiredMode.REQUIRED, example = "트랜잭션은 여러 작업을 하나의 단위로 처리합니다.", maxLength = 1000000)
        String body,
        @NotNull @PositiveOrZero @Schema(description = "조회 또는 직전 저장 응답의 version. 0 이상. 저장 성공마다 1 증가하므로 응답 값으로 갱신하세요.", requiredMode = Schema.RequiredMode.REQUIRED, example = "0", minimum = "0")
        Integer version) { }
