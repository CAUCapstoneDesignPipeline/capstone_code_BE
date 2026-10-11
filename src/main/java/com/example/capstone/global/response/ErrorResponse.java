package com.example.capstone.global.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.example.capstone.global.exception.ErrorCode;

public record ErrorResponse(
        @Schema(description = "실패 응답. HTTP 상태와 code를 함께 확인하세요.", requiredMode = Schema.RequiredMode.REQUIRED) ErrorBody error) {

    public ErrorResponse {
        Objects.requireNonNull(error, "error");
    }

    public static ErrorResponse of(ErrorCode code, String message, Map<String, ?> details) {
        return new ErrorResponse(new ErrorBody(code, message, details));
    }

    public record ErrorBody(
            @Schema(description = "FE 오류 분기 기준. 동일한 409에도 충돌 종류가 다르므로 코드로 구분하세요.", requiredMode = Schema.RequiredMode.REQUIRED, example = "NOTE_CONFLICT")
            ErrorCode code,
            @Schema(description = "사용자에게 표시할 한국어 안내 문구", requiredMode = Schema.RequiredMode.REQUIRED, example = "다른 곳에서 이 노트가 먼저 수정되었습니다.")
            String message,
            @Schema(description = "필요할 때만 포함. fields: [{field, reason}] 입력 오류, titles: 충돌 제목 배열, current: 최신 NoteResponse 또는 TopicListResponse. current로 미저장 입력을 자동 덮어쓰지 마세요.")
            @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, ?> details) {

        public ErrorBody {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
        }
    }

    public record FieldViolation(
        @Schema(description = "잘못된 요청 필드", example = "title") String field,
        @Schema(description = "입력 오류 사유", example = "invalid") String reason) {
    }
}
