package com.example.capstone.mcp.tool;

import java.util.LinkedHashMap;
import java.util.Map;

import com.example.capstone.global.exception.ErrorCode;
import com.example.capstone.note.dto.response.NoteResponse;

/**
 * BE 오류를 설계안 2.6의 도구 오류 모양 {code, message, retryable, nextStep, details}로 바꾼다.
 * code와 message는 BE 값을 그대로 쓰고 retryable·nextStep만 MCP 계층이 붙인다.
 */
public final class McpErrors {

    private McpErrors() {
    }

    public static Map<String, Object> error(ErrorCode code, String message, Map<String, ?> details) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code.name());
        error.put("message", message);
        error.put("retryable", retryable(code));
        error.put("nextStep", nextStep(code));
        Map<String, ?> shown = details(code, details);
        if (!shown.isEmpty()) {
            error.put("details", shown);
        }
        return error;
    }

    static boolean retryable(ErrorCode code) {
        return switch (code) {
            case VALIDATION_FAILED, NOTE_TITLE_TAKEN, TOPIC_ORDER_CONFLICT, INTERNAL -> true;
            default -> false;
        };
    }

    static String nextStep(ErrorCode code) {
        return switch (code) {
            case VALIDATION_FAILED -> "details.fields가 있으면 그 필드와 이유(reason)에 맞게 입력을 고쳐 다시 부른다.";
            case NOT_FOUND -> "id가 틀렸거나 지워졌다. search_notes나 list_topics로 다시 찾는다.";
            case NOTE_TITLE_TAKEN -> "details.titles의 제목이 이미 있다. 어떤 제목을 쓸지 사용자에게 묻는다.";
            case NOTE_CONFLICT -> "get_note로 최신 내용을 다시 읽고, 바뀐 점을 사용자에게 알린 뒤 어떻게 할지 물어본다.";
            case TOPIC_NAME_TAKEN -> "같은 이름의 주제가 있다. list_topics로 찾아 그 주제를 쓴다.";
            case TOPIC_ORDER_CONFLICT -> "주제 목록이 바뀌었다. list_topics를 다시 부른다.";
            case AI_UNAVAILABLE -> "추천 기능을 지금 쓸 수 없다고 사용자에게 알린다. 다시 시도하지 않는다.";
            case RECOMMENDATION_STALE, REVIEW_CONFLICT -> "근거가 바뀌었거나 이미 답한 추천이다. 사용자에게 알린다.";
            case INTERNAL -> "한 번만 다시 시도하고, 또 실패하면 사용자에게 알린다.";
            default -> "오류 내용을 사용자에게 알린다.";
        };
    }

    /** NOTE_CONFLICT의 details.current(노트 전체)는 본문을 빼고 버전과 수정 시각만 남긴다. */
    private static Map<String, ?> details(ErrorCode code, Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        if (code == ErrorCode.NOTE_CONFLICT) {
            Map<String, Object> shown = new LinkedHashMap<>();
            if (details.get("current") instanceof NoteResponse current) {
                shown.put("currentVersion", current.version());
                shown.put("currentUpdatedAt", current.updatedAt());
            }
            return shown;
        }
        return details;
    }
}
