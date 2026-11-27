package com.example.capstone.analysis.controller;

import java.util.Map;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.capstone.analysis.dto.response.DeveloperAnalysisResetResponse;
import com.example.capstone.analysis.service.DeveloperAnalysisResetService;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;

@RestController
@Tag(name = "개발자")
@SecurityRequirement(name = "bearerAuth")
public class DeveloperAnalysisResetController {
    private final DeveloperAnalysisResetService reset;

    public DeveloperAnalysisResetController(DeveloperAnalysisResetService reset) {
        this.reset = reset;
    }

    @Operation(summary = "계정의 분석 결과 초기화",
            description = "userId 계정의 분석 작업과 거기서 나온 키워드·관계, 추천 결과, 피드백, 검토 이력을 모두 지웁니다. "
                    + "노트와 주제는 그대로이며, 같은 노트를 다시 분석하면 키워드와 추천이 처음처럼 나옵니다. "
                    + "개발자 계정이 아니면 403, 없는 계정은 404, UUID 형식이 아니면 400입니다.")
    @PostMapping("/api/developer/users/{userId}/analysis-reset")
    public ResponseEntity<DeveloperAnalysisResetResponse> reset(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(reset.reset(UUID.fromString(jwt.getSubject()), id(userId)));
    }

    private static UUID id(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "요청 값이 올바르지 않습니다.", Map.of());
        }
    }
}
