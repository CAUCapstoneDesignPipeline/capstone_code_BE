package com.example.capstone.auth.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;

public final class ReturnToService {
    private ReturnToService() { }
    public static String validate(String value) {
        if (value == null) { return "/"; }
        String decoded = value;
        try {
            for (int depth = 0; depth < 5; depth++) {
                if (decoded.codePointCount(0, decoded.length()) > 500 || !decoded.startsWith("/")
                        || decoded.startsWith("//") || decoded.indexOf('\\') >= 0
                        || decoded.codePoints().anyMatch(Character::isISOControl)) { throw invalid(); }
                if (!decoded.contains("%")) { return value; }
                decoded = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
            }
        } catch (IllegalArgumentException exception) { throw invalid(); }
        throw invalid();
    }
    private static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "로그인 후 이동 경로가 올바르지 않습니다.",
                Map.of("fields", List.of(Map.of("field", "returnTo", "reason", "invalid"))));
    }
}
