package com.example.capstone.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    TOPIC_NAME_TAKEN(HttpStatus.CONFLICT),
    TOPIC_ORDER_CONFLICT(HttpStatus.CONFLICT),
    NOTE_TITLE_TAKEN(HttpStatus.CONFLICT),
    NOTE_CONFLICT(HttpStatus.CONFLICT),
    CANDIDATE_CLOSED(HttpStatus.CONFLICT),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
