package com.example.capstone.analysis.controller;

import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.example.capstone.analysis.dto.response.DeveloperAnalysisJobListResponse;
import com.example.capstone.analysis.dto.response.DeveloperAnalysisJobResponse;
import com.example.capstone.analysis.service.DeveloperAnalysisJobService;
import com.example.capstone.auth.service.DeveloperAccessService;
import com.example.capstone.global.response.ErrorResponse;

@RestController
@Tag(name = "개발자")
@SecurityRequirement(name = "bearerAuth")
public class DeveloperAnalysisJobController {
    private final DeveloperAccessService access;
    private final DeveloperAnalysisJobService jobs;

    public DeveloperAnalysisJobController(DeveloperAccessService access, DeveloperAnalysisJobService jobs) {
        this.access = access;
        this.jobs = jobs;
    }

    @Operation(summary = "분석 작업 목록 (개발자)",
            description = "모든 사용자의 분석 작업을 최신순으로 준다. 개발자 계정 확인을 입력 검증보다 먼저 한다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "최신순 목록, Cache-Control:no-store",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = DeveloperAnalysisJobListResponse.class))),
        @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: status·from·to·limit 값이 잘못됨",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "FORBIDDEN: 개발자 계정이 아님",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/api/developer/analysis-jobs")
    public ResponseEntity<DeveloperAnalysisJobListResponse> list(@AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "pending · running · succeeded · failed", schema = @Schema(allowableValues = {"pending", "running", "succeeded", "failed"}))
            @RequestParam(required = false) String status,
            @Parameter(description = "createdAt 하한(포함), RFC 3339", schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(required = false) String from,
            @Parameter(description = "createdAt 상한(제외), RFC 3339", schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(required = false) String to,
            @Parameter(description = "1~100, 기본 50", schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "50"))
            @RequestParam(required = false) String limit) {
        access.require(UUID.fromString(jwt.getSubject()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(jobs.list(status, from, to, limit));
    }

    @Operation(summary = "분석 작업 상세 (개발자)",
            description = "단계별 operation·시도 횟수·소요 시간, 입력 노트별 캐시 사용 여부, 결과 수와 피드백 집계를 준다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "작업 상세, Cache-Control:no-store",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = DeveloperAnalysisJobResponse.class))),
        @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: jobId가 정규 UUID가 아님",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "FORBIDDEN: 개발자 계정이 아님",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "NOT_FOUND: 없는 작업",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/api/developer/analysis-jobs/{jobId}")
    public ResponseEntity<DeveloperAnalysisJobResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String jobId) {
        access.require(UUID.fromString(jwt.getSubject()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(jobs.get(jobId));
    }
}
