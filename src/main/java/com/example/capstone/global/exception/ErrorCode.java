package com.example.capstone.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    TOPIC_NAME_TAKEN(HttpStatus.CONFLICT),
    TOPIC_ORDER_CONFLICT(HttpStatus.CONFLICT),
    NOTE_TITLE_TAKEN(HttpStatus.CONFLICT),
    NOTE_DELETE_BLOCKED(HttpStatus.CONFLICT),
    NOTE_CONFLICT(HttpStatus.CONFLICT),
    REVIEW_CONFLICT(HttpStatus.CONFLICT),
    CANDIDATE_CLOSED(HttpStatus.CONFLICT),
    AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    UPLOAD_LIMIT_EXCEEDED(HttpStatus.PAYLOAD_TOO_LARGE),
    UNSUPPORTED_FILE_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    CONVERSION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    CONVERSION_CONFLICT(HttpStatus.CONFLICT),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
