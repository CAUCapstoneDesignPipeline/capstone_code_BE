package com.example.capstone.mcp.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.validation.TextValidation;

/**
 * 도구 입력을 입력 스키마대로 검사한다. 어긋나면 BE와 같은 VALIDATION_FAILED(details.fields)로 실패한다.
 * 길이는 유니코드 코드 포인트로 센다.
 */
public final class McpArguments {

    private static final Pattern UUID_TEXT =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final Map<String, Object> values;

    public McpArguments(Map<String, Object> values, Set<String> allowed) {
        this.values = values;
        for (String name : values.keySet()) {
            if (!allowed.contains(name)) {
                throw invalid(name, "unknown");
            }
        }
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    public String optionalString(String name, int maxCodePoints) {
        Object value = values.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw invalid(name, "invalid");
        }
        if (text.codePointCount(0, text.length()) > maxCodePoints) {
            throw invalid(name, "too_long");
        }
        return text;
    }

    public String requiredString(String name) {
        Object value = values.get(name);
        if (value == null) {
            throw invalid(name, "required");
        }
        if (!(value instanceof String text)) {
            throw invalid(name, "invalid");
        }
        return text;
    }

    public UUID requiredUuid(String name) {
        return parseUuid(name, requiredString(name));
    }

    public UUID nullableUuid(String name) {
        if (!values.containsKey(name)) {
            throw invalid(name, "required");
        }
        Object value = values.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw invalid(name, "invalid");
        }
        return parseUuid(name, text);
    }

    public String optionalEnum(String name, List<String> allowed, String defaultValue) {
        Object value = values.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof String text) || !allowed.contains(text)) {
            throw invalid(name, "invalid");
        }
        return text;
    }

    public int optionalInt(String name, int min, int max, int defaultValue) {
        Object value = values.get(name);
        if (value == null) {
            return defaultValue;
        }
        return range(name, value, min, max);
    }

    public int requiredInt(String name, int min, int max) {
        Object value = values.get(name);
        if (value == null) {
            throw invalid(name, "required");
        }
        return range(name, value, min, max);
    }

    public List<UUID> requiredUuidList(String name, int minItems, int maxItems) {
        Object value = values.get(name);
        if (value == null) {
            throw invalid(name, "required");
        }
        if (!(value instanceof List<?> list)) {
            throw invalid(name, "invalid");
        }
        if (list.size() < minItems || list.size() > maxItems) {
            throw invalid(name, "out_of_range");
        }
        List<UUID> ids = new java.util.ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text)) {
                throw invalid(name, "invalid");
            }
            UUID id = parseUuid(name, text);
            if (ids.contains(id)) {
                throw invalid(name, "duplicate");
            }
            ids.add(id);
        }
        return ids;
    }

    private static int range(String name, Object value, int min, int max) {
        long number;
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            number = ((Number) value).longValue();
        } else if (value instanceof Number decimal && decimal.doubleValue() == Math.rint(decimal.doubleValue())
                && !Double.isInfinite(decimal.doubleValue())) {
            number = (long) decimal.doubleValue();
        } else {
            throw invalid(name, "invalid");
        }
        if (number < min || number > max) {
            throw invalid(name, "out_of_range");
        }
        return (int) number;
    }

    private static UUID parseUuid(String name, String text) {
        if (!UUID_TEXT.matcher(text).matches()) {
            throw invalid(name, "invalid");
        }
        return UUID.fromString(text);
    }

    public static ApiException invalid(String field, String reason) {
        return TextValidation.invalid(field, reason, "요청 값이 올바르지 않습니다.");
    }
}
