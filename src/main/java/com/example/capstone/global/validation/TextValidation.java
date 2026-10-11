package com.example.capstone.global.validation;

import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;

public final class TextValidation {
    private TextValidation() { }
    public static String title(String raw, String field, String label, int max) {
        String value = raw == null ? "" : Normalizer.normalize(raw.trim(), Normalizer.Form.NFC);
        if (value.isEmpty()) { throw invalid(field,"empty", label + "을 입력하세요."); }
        if (value.contains("/")) { throw invalid(field,"contains_slash",label + "에는 '/'를 쓸 수 없습니다."); }
        if (value.codePointCount(0,value.length()) > max) { throw invalid(field,"too_long",label + "은 " + max + "자 이하로 입력하세요."); }
        return value;
    }
    public static String body(String raw) {
        String value = Normalizer.normalize(raw, Normalizer.Form.NFC);
        if (value.codePointCount(0,value.length()) > 1000000) {
            throw invalid("body","too_long","본문은 1000000자 이하로 입력하세요.");
        }
        return value;
    }
    public static ApiException invalid(String field, String reason, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED,message,
                Map.of("fields",List.of(Map.of("field",field,"reason",reason))));
    }
}
