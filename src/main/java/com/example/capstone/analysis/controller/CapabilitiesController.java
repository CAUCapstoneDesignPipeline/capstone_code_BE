package com.example.capstone.analysis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.capstone.analysis.config.AiProperties;
import com.example.capstone.analysis.dto.response.CapabilitiesResponse;

@RestController
@Tag(name = "기능")
public class CapabilitiesController {
    private final AiProperties properties;

    public CapabilitiesController(AiProperties properties) {
        this.properties = properties;
    }

    @Operation(summary = "AI 기능 사용 가능 여부 (계약 검토 대기)",
            description = "공개 조회이며 세 값 모두 false와 Cache-Control:no-store를 반환합니다. AI-off 계약 승인 대기 구현입니다.")
    @SecurityRequirements
    @GetMapping("/api/capabilities")
    public ResponseEntity<CapabilitiesResponse> get() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CapabilitiesResponse(properties.enabled(), false, false));
    }
}
