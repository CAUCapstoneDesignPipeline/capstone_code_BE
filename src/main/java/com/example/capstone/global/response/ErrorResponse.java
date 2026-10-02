package com.example.capstone.global.response;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.example.capstone.global.exception.ErrorCode;

public record ErrorResponse(ErrorBody error) {

    public ErrorResponse {
        Objects.requireNonNull(error, "error");
    }

    public static ErrorResponse of(ErrorCode code, String message, Map<String, ?> details) {
        return new ErrorResponse(new ErrorBody(code, message, details));
    }

    public record ErrorBody(
            ErrorCode code,
            String message,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, ?> details) {

        public ErrorBody {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
        }
    }

    public record FieldViolation(String field, String reason) {
    }
}
