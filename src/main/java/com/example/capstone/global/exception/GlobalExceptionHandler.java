package com.example.capstone.global.exception;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.example.capstone.global.response.ErrorResponse;
import com.example.capstone.global.response.ErrorResponse.FieldViolation;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String INVALID_REQUEST = "요청 값이 올바르지 않습니다.";
    private static final String INTERNAL_ERROR = "일시적인 오류가 발생했습니다. 잠시 후 다시 시도하세요.";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.errorCode().status())
                .body(ErrorResponse.of(exception.errorCode(), exception.getMessage(), exception.details()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        List<FieldViolation> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), validationReason(error)))
                .distinct()
                .toList();
        Map<String, ?> details = fields.isEmpty() ? Map.of() : Map.of("fields", fields);
        return handleExceptionInternal(exception,
                ErrorResponse.of(ErrorCode.VALIDATION_FAILED, INVALID_REQUEST, details), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // Keep MVC status and protocol headers (for example Allow on 405), but use our JSON contract.
        ErrorResponse response = body instanceof ErrorResponse error ? error : frameworkError(status);
        return super.createResponseEntity(response, headers, status, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
        // Exception messages and causes can contain SQL, credentials or note contents.
        log.error("Unhandled API exception type: {}", exception.getClass().getName());
        return ResponseEntity.internalServerError()
                .body(ErrorResponse.of(ErrorCode.INTERNAL, INTERNAL_ERROR, Map.of()));
    }

    private static ErrorResponse frameworkError(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return ErrorResponse.of(ErrorCode.INTERNAL, INTERNAL_ERROR, Map.of());
        }
        if (status.value() == 404) {
            return ErrorResponse.of(ErrorCode.NOT_FOUND, "요청한 대상을 찾을 수 없습니다.", Map.of());
        }
        if (status.value() == 401) {
            return ErrorResponse.of(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다.", Map.of());
        }
        return ErrorResponse.of(ErrorCode.VALIDATION_FAILED, INVALID_REQUEST, Map.of());
    }

    private static String validationReason(FieldError error) {
        String code = error.getCode();
        if (error.getRejectedValue() instanceof CharSequence value
                && (("NotBlank".equals(code) && value.toString().isBlank())
                    || ("NotEmpty".equals(code) && value.isEmpty()))) {
            return "empty";
        }
        // Domain validation supplies precise reasons such as too_long and contains_slash via ApiException.
        return "invalid";
    }
}
