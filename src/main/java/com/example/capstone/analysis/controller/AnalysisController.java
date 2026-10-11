package com.example.capstone.analysis.controller;

import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import com.example.capstone.analysis.dto.response.NoteAnalysisResponse;
import com.example.capstone.analysis.service.AnalysisService;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;
import com.example.capstone.global.response.ErrorResponse;

@RestController
@Tag(name = "분석")
@SecurityRequirement(name = "bearerAuth")
public class AnalysisController {
    private static final String INVALID = "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"요청 값이 올바르지 않습니다.\"}}";
    private static final String UNAUTHENTICATED = "{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"로그인이 필요합니다.\"}}";
    private static final String NOT_FOUND = "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"삭제된 노트입니다\"}}";
    private static final String UNAVAILABLE = "{\"error\":{\"code\":\"AI_UNAVAILABLE\",\"message\":\"AI 분석은 아직 사용할 수 없습니다.\",\"details\":{\"reason\":\"NOT_DEPLOYED\"}}}";
    private final AnalysisService analysis;

    public AnalysisController(AnalysisService analysis) {
        this.analysis = analysis;
    }

    @Operation(summary = "AI-off: 실제 최근 분석 작업 (계약 검토 대기)",
            description = "소유한 노트의 실제 최근 작업 또는 job:null. 내부 error_message는 공개하지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "최근 실제 작업 또는 job:null, Cache-Control:no-store",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = NoteAnalysisResponse.class))),
        @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: 정규 UUID를 입력하세요.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(name = "VALIDATION_FAILED", value = INVALID))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 로그인 후 다시 요청하세요.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(name = "UNAUTHENTICATED", value = UNAUTHENTICATED))),
        @ApiResponse(responseCode = "404", description = "NOT_FOUND: 없는 노트와 다른 사용자 노트는 구분하지 않습니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(name = "NOT_FOUND", value = NOT_FOUND)))
    })
    @GetMapping("/api/notes/{noteId}/analysis")
    public ResponseEntity<NoteAnalysisResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String noteId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(analysis.get(UUID.fromString(jwt.getSubject()), parseNoteId(noteId)));
    }

    @Operation(summary = "AI-off: 분석 요청 차단 (계약 검토 대기)",
            description = "인증·UUID·소유 확인 후 503 AI_UNAVAILABLE. 작업을 생성하거나 변경하지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: 정규 UUID를 입력하세요.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(name = "VALIDATION_FAILED", value = INVALID))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 로그인 후 다시 요청하세요.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(name = "UNAUTHENTICATED", value = UNAUTHENTICATED))),
        @ApiResponse(responseCode = "404", description = "NOT_FOUND: 없는 노트와 다른 사용자 노트는 구분하지 않습니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(name = "NOT_FOUND", value = NOT_FOUND))),
        @ApiResponse(responseCode = "503", description = "AI_UNAVAILABLE/NOT_DEPLOYED: 작업 생성·Retry-After 없음. 자동 retry·폴링을 시작하지 마세요.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class), examples = @ExampleObject(name = "AI_UNAVAILABLE", value = UNAVAILABLE)))
    })
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @PostMapping("/api/notes/{noteId}/analysis")
    public void request(@AuthenticationPrincipal Jwt jwt, @PathVariable String noteId) {
        analysis.request(UUID.fromString(jwt.getSubject()), parseNoteId(noteId));
    }

    private static UUID parseNoteId(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (id.toString().equalsIgnoreCase(value)) {
                return id;
            }
        } catch (IllegalArgumentException exception) {
            // Do not expose the raw path value or parser details.
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "요청 값이 올바르지 않습니다.");
    }
}
