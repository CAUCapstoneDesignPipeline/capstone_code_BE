package com.example.capstone.analysis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.capstone.analysis.service.AiActivationService;
import com.example.capstone.analysis.dto.response.CapabilitiesResponse;

@RestController
@Tag(name = "기능")
public class CapabilitiesController {
    private final AiActivationService properties;

    public CapabilitiesController(AiActivationService properties) {
        this.properties = properties;
    }

    @Operation(summary = "AI 기능 사용 가능 여부",
            description = "기본 AI-off는 세 값 모두 false입니다. 필수 설정과 3초 이내의 profile readiness 검사에 실패하면 false입니다.")
    @SecurityRequirements
    @GetMapping("/api/capabilities")
    public ResponseEntity<CapabilitiesResponse> get() {
        boolean available=properties.available();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CapabilitiesResponse(available, available, available));
    }
}
