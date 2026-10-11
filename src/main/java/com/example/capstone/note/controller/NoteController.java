package com.example.capstone.note.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import com.example.capstone.global.response.ErrorResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.DeleteMapping;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PutMapping;
import com.example.capstone.note.dto.request.NoteUpdateRequest;
import com.example.capstone.note.dto.request.NoteMoveRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.example.capstone.note.dto.request.NoteCreateRequest;
import com.example.capstone.note.dto.response.NoteResponse;
import com.example.capstone.note.dto.response.NoteListResponse;
import com.example.capstone.note.service.NoteService;

@RestController
@Tag(name = "노트")
@SecurityRequirement(name = "bearerAuth")
public class NoteController {
    private final NoteService notes;
    public NoteController(NoteService notes) { this.notes=notes; }
    @Operation(summary = "노트 생성",
            description = "title은 NFC 정규화·trim 후 1~200 코드 포인트이며 /를 금지합니다. topicId 생략 또는 null은 미분류입니다. body 생략은 빈 문자열, 명시적 null은 400입니다. 본문은 NFC 정규화하며 공백을 유지하고 최대 1,000,000 코드 포인트입니다. 같은 사용자·같은 주제(미분류 포함)의 동일 제목은 중복이며 대소문자를 구분합니다. 생성 version은 0입니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "생성 완료", content = @Content(mediaType = "application/json", schema = @Schema(implementation = NoteResponse.class))),
        @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: 요청 형식·필드 값을 확인하세요. error.details.fields가 있으면 field와 reason으로 입력 오류를 표시합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "VALIDATION_FAILED", value = "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"제목을 입력하세요.\",\"details\":{\"fields\":[{\"field\":\"title\",\"reason\":\"empty\"}]}}}")})),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 액세스 토큰이 없거나 만료되었습니다. refresh 성공 후 새 토큰으로 재시도하고, refresh도 401이면 로그인 화면으로 이동합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "UNAUTHENTICATED", value = "{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"로그인이 필요합니다.\"}}")})),
        @ApiResponse(responseCode = "404", description = "NOT_FOUND: 삭제되었거나 현재 사용자의 소유가 아닌 ID입니다. 타인의 데이터 존재 여부는 공개하지 않습니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "NOT_FOUND", value = "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"삭제된 주제입니다.\"}}")})),
        @ApiResponse(responseCode = "409", description = "NOTE_TITLE_TAKEN: 해당 주제에 같은 제목이 있습니다. details.titles를 안내하고 제목을 변경하세요.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "NOTE_TITLE_TAKEN", value = "{\"error\":{\"code\":\"NOTE_TITLE_TAKEN\",\"message\":\"같은 주제에 같은 제목의 노트가 있습니다.\",\"details\":{\"titles\":[\"트랜잭션\"]}}}")}))
    })
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/notes")
    public ResponseEntity<NoteResponse> create(@AuthenticationPrincipal Jwt jwt,@RequestBody NoteCreateRequest request) {
        return ResponseEntity.status(201).body(notes.create(UUID.fromString(jwt.getSubject()),request));
    }
    @Operation(summary = "노트 주제 이동 / 미분류 이동",
            description = "topicId 필드는 반드시 보내며 null이면 미분류로 이동합니다. 필드 생략은 400입니다. 본문·제목·version은 바뀌지 않습니다. 동시 자동 저장과 이동이 서로의 내용을 덮어쓰지 않습니다. 성공 후 주제 집계와 노트 목록을 갱신하세요.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "처리 성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = NoteResponse.class))),
        @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: 요청 형식·필드 값을 확인하세요. error.details.fields가 있으면 field와 reason으로 입력 오류를 표시합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "VALIDATION_FAILED", value = "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"요청 값이 올바르지 않습니다.\"}}")})),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 액세스 토큰이 없거나 만료되었습니다. refresh 성공 후 새 토큰으로 재시도하고, refresh도 401이면 로그인 화면으로 이동합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "UNAUTHENTICATED", value = "{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"로그인이 필요합니다.\"}}")})),
        @ApiResponse(responseCode = "404", description = "NOT_FOUND: 삭제되었거나 현재 사용자의 소유가 아닌 ID입니다. 타인의 데이터 존재 여부는 공개하지 않습니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "NOT_FOUND", value = "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"삭제된 노트입니다\"}}")})),
        @ApiResponse(responseCode = "409", description = "NOTE_TITLE_TAKEN: 목적지에 같은 제목이 있습니다. details.titles를 안내하세요. 원래 주제를 유지합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "NOTE_TITLE_TAKEN", value = "{\"error\":{\"code\":\"NOTE_TITLE_TAKEN\",\"message\":\"같은 주제에 같은 제목의 노트가 있습니다.\",\"details\":{\"titles\":[\"트랜잭션\"]}}}")}))
    })
    @PutMapping("/api/notes/{id}/topic")
    public NoteResponse move(@AuthenticationPrincipal Jwt jwt,@Parameter(description = "현재 사용자 소유의 UUID. 없거나 타인 소유이면 404입니다.") @PathVariable UUID id,@RequestBody NoteMoveRequest request) {
        return notes.move(UUID.fromString(jwt.getSubject()),id,request.topicId());
    }
    @Operation(summary = "노트 제목·본문 저장 (자동 저장)",
            description = "부분 수정이 아닌 전체 title·body·version을 보냅니다. 성공 시 내용이 같아도 version이 1 증가합니다. 다음 저장은 성공 응답의 version을 사용하세요. 409 NOTE_CONFLICT의 details.current는 최신 NoteResponse입니다. 자동 저장을 멈추고 사용자의 미저장 입력을 보존한 채 최신 내용 불러오기·재시도를 제공하세요. 404일 때도 편집 중인 입력을 버리지 마세요. 이 API는 topicId를 변경하지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "처리 성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = NoteResponse.class))),
        @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: 요청 형식·필드 값을 확인하세요. error.details.fields가 있으면 field와 reason으로 입력 오류를 표시합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "VALIDATION_FAILED", value = "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"요청 값이 올바르지 않습니다.\"}}")})),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 액세스 토큰이 없거나 만료되었습니다. refresh 성공 후 새 토큰으로 재시도하고, refresh도 401이면 로그인 화면으로 이동합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "UNAUTHENTICATED", value = "{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"로그인이 필요합니다.\"}}")})),
        @ApiResponse(responseCode = "404", description = "NOT_FOUND: 삭제되었거나 현재 사용자의 소유가 아닌 ID입니다. 타인의 데이터 존재 여부는 공개하지 않습니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "NOT_FOUND", value = "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"다른 곳에서 삭제된 노트입니다\"}}")})),
        @ApiResponse(responseCode = "409", description = "NOTE_CONFLICT: 오래된 version 또는 동시 수정입니다(details.current=최신 노트). NOTE_TITLE_TAKEN: 제목 중복입니다(details.titles). error.code로 구분하고 미저장 입력을 유지하세요.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "NOTE_CONFLICT", value = "{\"error\":{\"code\":\"NOTE_CONFLICT\",\"message\":\"다른 곳에서 이 노트가 먼저 수정되었습니다.\",\"details\":{\"current\":{\"id\":\"3fa85f64-5717-4562-b3fc-2c963f66afa6\",\"topicId\":null,\"title\":\"트랜잭션\",\"body\":\"다른 탭에서 저장한 본문\",\"version\":1,\"createdAt\":\"2026-10-02T00:00:00Z\",\"updatedAt\":\"2026-10-02T00:01:00Z\"}}}}"),
                                @ExampleObject(name = "NOTE_TITLE_TAKEN", value = "{\"error\":{\"code\":\"NOTE_TITLE_TAKEN\",\"message\":\"같은 주제에 같은 제목의 노트가 있습니다.\",\"details\":{\"titles\":[\"트랜잭션\"]}}}")}))
    })
    @PutMapping("/api/notes/{id}")
    public NoteResponse update(@AuthenticationPrincipal Jwt jwt,@Parameter(description = "현재 사용자 소유의 UUID. 없거나 타인 소유이면 404입니다.") @PathVariable UUID id,@Valid @RequestBody NoteUpdateRequest request) {
        return notes.update(UUID.fromString(jwt.getSubject()),id,request);
    }
    @Operation(summary = "노트 원문 조회",
            description = "편집기를 열 때 호출합니다. 전체 body와 현재 version을 반환하며 topicId=null이면 미분류입니다. 저장 요청에는 이 응답 또는 직전 저장 응답의 version을 사용하세요.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "처리 성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = NoteResponse.class))),
        @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: 요청 형식·필드 값을 확인하세요. error.details.fields가 있으면 field와 reason으로 입력 오류를 표시합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "VALIDATION_FAILED", value = "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"요청 값이 올바르지 않습니다.\"}}")})),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 액세스 토큰이 없거나 만료되었습니다. refresh 성공 후 새 토큰으로 재시도하고, refresh도 401이면 로그인 화면으로 이동합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "UNAUTHENTICATED", value = "{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"로그인이 필요합니다.\"}}")})),
        @ApiResponse(responseCode = "404", description = "NOT_FOUND: 삭제되었거나 현재 사용자의 소유가 아닌 ID입니다. 타인의 데이터 존재 여부는 공개하지 않습니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "NOT_FOUND", value = "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"삭제된 노트입니다\"}}")}))
    })
    @GetMapping("/api/notes/{id}")
    public NoteResponse get(@AuthenticationPrincipal Jwt jwt,@Parameter(description = "현재 사용자 소유의 UUID. 없거나 타인 소유이면 404입니다.") @PathVariable UUID id) { return notes.get(UUID.fromString(jwt.getSubject()),id); }
    @Operation(summary = "노트 삭제",
            description = "성공은 본문 없는 204입니다. FE는 노트 목록·주제 집계와 선택된 편집기를 갱신하세요. 현재 AI 근거가 연결된 노트는 NOTE_DELETE_BLOCKED로 삭제를 차단합니다. AI 근거 소실·연결 관계/후보 후처리는 후속 구현 대상이므로 차단을 임의로 우회하지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "삭제/로그아웃 완료 (본문 없음)", content = @Content),
        @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: 요청 형식·필드 값을 확인하세요. error.details.fields가 있으면 field와 reason으로 입력 오류를 표시합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "VALIDATION_FAILED", value = "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"요청 값이 올바르지 않습니다.\"}}")})),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 액세스 토큰이 없거나 만료되었습니다. refresh 성공 후 새 토큰으로 재시도하고, refresh도 401이면 로그인 화면으로 이동합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "UNAUTHENTICATED", value = "{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"로그인이 필요합니다.\"}}")})),
        @ApiResponse(responseCode = "404", description = "NOT_FOUND: 삭제되었거나 현재 사용자의 소유가 아닌 ID입니다. 타인의 데이터 존재 여부는 공개하지 않습니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "NOT_FOUND", value = "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"삭제된 노트입니다\"}}")})),
        @ApiResponse(responseCode = "409", description = "NOTE_DELETE_BLOCKED: 근거가 연결된 노트는 아직 삭제할 수 없습니다. 안내를 표시하고 노트를 유지하세요. details는 없습니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "NOTE_DELETE_BLOCKED", value = "{\"error\":{\"code\":\"NOTE_DELETE_BLOCKED\",\"message\":\"근거가 연결된 노트는 아직 삭제할 수 없습니다.\"}}")}))
    })
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/api/notes/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt,@Parameter(description = "현재 사용자 소유의 UUID. 없거나 타인 소유이면 404입니다.") @PathVariable UUID id) {
        notes.delete(UUID.fromString(jwt.getSubject()),id);
        return ResponseEntity.noContent().build();
    }
    @Operation(summary = "노트 목록 / 제목·본문 검색",
            description = "topicId 생략은 전체, none은 미분류, UUID는 본인 주제입니다. sort=title은 제목 오름차순, updated는 최근 수정 순입니다. q는 앞뒤 공백 제거 후 최대 100 Unicode 코드 포인트이며 빈 검색어는 검색하지 않습니다. 제목·본문을 대소문자 구분 없이 부분 검색하고 %, _, 역슬래시는 와일드카드가 아닌 문자로 취급합니다. 목록에는 body가 없고 최대 80 코드 포인트의 평문 snippet만 있습니다. 본문 최초 일치 주변(앞쪽 최대 20개)을 표시하며 제목만 일치하면 본문 앞부분입니다. snippet의 Markdown·HTML을 실행하지 말고 텍스트로 표시하세요. 편집 시 단건 조회로 원문을 읽으세요.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "처리 성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = NoteListResponse.class))),
        @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: 요청 형식·필드 값을 확인하세요. error.details.fields가 있으면 field와 reason으로 입력 오류를 표시합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "VALIDATION_FAILED", value = "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"요청 값이 올바르지 않습니다.\"}}")})),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 액세스 토큰이 없거나 만료되었습니다. refresh 성공 후 새 토큰으로 재시도하고, refresh도 401이면 로그인 화면으로 이동합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "UNAUTHENTICATED", value = "{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"로그인이 필요합니다.\"}}")})),
        @ApiResponse(responseCode = "404", description = "NOT_FOUND: 삭제되었거나 현재 사용자의 소유가 아닌 ID입니다. 타인의 데이터 존재 여부는 공개하지 않습니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "NOT_FOUND", value = "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"삭제된 주제입니다.\"}}")}))
    })
    @GetMapping("/api/notes")
    public NoteListResponse list(@AuthenticationPrincipal Jwt jwt,@Parameter(description = "생략: 전체 / none: 미분류 / 본인 주제 UUID: 해당 주제", example = "none") @RequestParam(required=false) String topicId,
            @Parameter(description = "title: 제목 오름차순 / updated: 최근 수정 순", schema = @Schema(allowableValues = {"title", "updated"})) @RequestParam(defaultValue="title") String sort,@Parameter(description = "제목·본문 부분 검색. trim 후 최대 100 코드 포인트. 특수 문자도 문자 그대로 검색합니다.", example = "트랜잭션") @RequestParam(required=false) String q) {
        return notes.list(UUID.fromString(jwt.getSubject()),topicId,sort,q);
    }
}
