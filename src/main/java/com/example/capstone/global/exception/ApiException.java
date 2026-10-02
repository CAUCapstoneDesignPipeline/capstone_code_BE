package com.example.capstone.global.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, ?> details;

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of());
    }

    // message and details are public API data; never pass an internal exception message or raw input.
    public ApiException(ErrorCode errorCode, String message, Map<String, ?> details) {
        super(Objects.requireNonNull(message, "message"));
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, ?> details() {
        return details;
    }
}
