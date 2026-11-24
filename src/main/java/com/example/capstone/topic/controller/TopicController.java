package com.example.capstone.topic.controller;

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
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import com.example.capstone.topic.dto.request.TopicOrderRequest;
import com.example.capstone.topic.dto.request.TopicMoveRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.example.capstone.topic.dto.request.TopicNameRequest;
import com.example.capstone.topic.dto.response.TopicResponse;
import com.example.capstone.topic.dto.response.TopicListResponse;
import com.example.capstone.topic.service.TopicService;

@RestController
@Tag(name = "주제")
@SecurityRequirement(name = "bearerAuth")
public class TopicController {
    private final TopicService topics;
    public TopicController(TopicService topics) { this.topics=topics; }
    @Operation(summary = "주제 삭제 / 하위 주제와 노트는 한 단계 위로 이동",
            description = "주제만 삭제합니다. 바로 아래 하위 주제와 노트는 삭제한 주제의 부모로 옮기며, 최상위 주제였다면 하위 주제는 최상위로, 노트는 미분류로 갑니다. 노트 본문·version은 유지됩니다. 옮길 곳에 같은 제목의 노트(NOTE_TITLE_TAKEN, details.titles)나 같은 이름의 주제(TOPIC_NAME_TAKEN, details.names)가 있으면 전체 작업을 취소합니다. 성공은 본문 없는 204이며 FE는 주제 목록·노트 목록·집계를 갱신하세요.")
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
                        examples = {@ExampleObject(name = "NOT_FOUND", value = "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"삭제된 주제입니다.\"}}")})),
        @ApiResponse(responseCode = "409", description = "NOTE_TITLE_TAKEN: 옮길 곳(미분류 또는 상위 주제)에 동일 제목이 있습니다. details.titles를 안내하고 충돌 제목을 해결한 뒤 재시도하세요. TOPIC_NAME_TAKEN이면 details.names의 하위 주제 이름이 상위에서 겹칩니다. 삭제·이동은 모두 취소됩니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "NOTE_TITLE_TAKEN", value = "{\"error\":{\"code\":\"NOTE_TITLE_TAKEN\",\"message\":\"미분류에 같은 제목의 노트가 있어 주제를 삭제할 수 없습니다.\",\"details\":{\"titles\":[\"트랜잭션\"]}}}")}))
    })
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/api/topics/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt,@Parameter(description = "현재 사용자 소유의 UUID. 없거나 타인 소유이면 404입니다.") @PathVariable UUID id) {
        topics.delete(UUID.fromString(jwt.getSubject()),id);
        return ResponseEntity.noContent().build();
    }
    @Operation(summary = "내 주제 목록과 노트 수 조회",
            description = "본인 주제만 sortOrder 순서로 반환합니다. topics가 비어도 200입니다. 각 주제의 noteCount와 unassignedNoteCount를 주제 트리·미분류 배지에 사용하세요.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "처리 성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = TopicListResponse.class))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 액세스 토큰이 없거나 만료되었습니다. refresh 성공 후 새 토큰으로 재시도하고, refresh도 401이면 로그인 화면으로 이동합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "UNAUTHENTICATED", value = "{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"로그인이 필요합니다.\"}}")}))
    })
    @GetMapping("/api/topics")
    public TopicListResponse list(@AuthenticationPrincipal Jwt jwt) { return topics.list(UUID.fromString(jwt.getSubject())); }
    @Operation(summary = "한 부모 안의 주제 순서 저장",
            description = "parentId(생략·null이면 최상위)의 바로 아래 주제 ID를 빠짐없이 원하는 순서로 한 번씩 보내세요. sortOrder는 그 부모 안에서 0부터 다시 부여합니다. 하위 주제가 없을 때만 빈 배열이 성공합니다. parentId가 없거나 타인 소유여도 TOPIC_ORDER_CONFLICT입니다. 누락·추가 등 현재 목록과 다르면 TOPIC_ORDER_CONFLICT이며 details.current의 TopicListResponse로 목록을 갱신한 뒤 다시 정렬하세요. 중복·null ID는 입력 오류입니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "처리 성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = TopicListResponse.class))),
        @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: 요청 형식·필드 값을 확인하세요. error.details.fields가 있으면 field와 reason으로 입력 오류를 표시합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "VALIDATION_FAILED", value = "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"주제 순서가 올바르지 않습니다.\",\"details\":{\"fields\":[{\"field\":\"topicIds\",\"reason\":\"invalid\"}]}}}")})),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 액세스 토큰이 없거나 만료되었습니다. refresh 성공 후 새 토큰으로 재시도하고, refresh도 401이면 로그인 화면으로 이동합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "UNAUTHENTICATED", value = "{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"로그인이 필요합니다.\"}}")})),
        @ApiResponse(responseCode = "409", description = "TOPIC_ORDER_CONFLICT: 목록이 바뀌었습니다. error.details.current를 표시한 뒤 재시도하세요.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "TOPIC_ORDER_CONFLICT", value = "{\"error\":{\"code\":\"TOPIC_ORDER_CONFLICT\",\"message\":\"다른 곳에서 주제 목록이 바뀌었습니다. 목록을 새로 불러왔으니 다시 시도하세요.\",\"details\":{\"current\":{\"topics\":[],\"unassignedNoteCount\":0}}}}")}))
    })
    @PutMapping("/api/topics/order")
    public TopicListResponse reorder(@AuthenticationPrincipal Jwt jwt,@RequestBody TopicOrderRequest request) {
        return topics.reorder(UUID.fromString(jwt.getSubject()),request.parentId(),request.topicIds());
    }
    @Operation(summary = "주제 생성",
            description = "name을 NFC 정규화하고 앞뒤 공백을 제거한 뒤 검증합니다. 1~50 Unicode 코드 포인트이며 /를 금지합니다. parentId를 주면 그 주제의 하위 주제로 만들며 깊이 제한은 없습니다. 같은 부모 안의 동일 이름은 중복이고 대소문자를 구분합니다. 새 주제는 그 부모의 마지막 순서로 추가되며 첫 sortOrder는 0입니다. 타인 소유이거나 없는 parentId는 404입니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "생성 완료", content = @Content(mediaType = "application/json", schema = @Schema(implementation = TopicResponse.class))),
        @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: 요청 형식·필드 값을 확인하세요. error.details.fields가 있으면 field와 reason으로 입력 오류를 표시합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "VALIDATION_FAILED", value = "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"주제 이름을 입력하세요.\",\"details\":{\"fields\":[{\"field\":\"name\",\"reason\":\"empty\"}]}}}")})),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 액세스 토큰이 없거나 만료되었습니다. refresh 성공 후 새 토큰으로 재시도하고, refresh도 401이면 로그인 화면으로 이동합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "UNAUTHENTICATED", value = "{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"로그인이 필요합니다.\"}}")})),
        @ApiResponse(responseCode = "409", description = "TOPIC_NAME_TAKEN: 같은 이름의 주제가 있습니다. 이름을 바꿔 다시 요청하세요.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "TOPIC_NAME_TAKEN", value = "{\"error\":{\"code\":\"TOPIC_NAME_TAKEN\",\"message\":\"같은 이름의 주제가 이미 있습니다.\"}}")})),
        @ApiResponse(responseCode = "404", description = "NOT_FOUND: parentId가 삭제되었거나 현재 사용자의 소유가 아닙니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "NOT_FOUND", value = "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"삭제된 주제입니다.\"}}")}))
    })
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/topics")
    public ResponseEntity<TopicResponse> create(@AuthenticationPrincipal Jwt jwt,@RequestBody TopicNameRequest request) {
        return ResponseEntity.status(201).body(topics.create(UUID.fromString(jwt.getSubject()),request.name(),request.parentId()));
    }
    @Operation(summary = "주제 이름 변경",
            description = "본인 주제의 이름을 변경합니다. 생성과 동일한 이름 검증·중복 규칙을 적용합니다. 자기 자신의 기존 이름으로 변경할 수 있습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "처리 성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = TopicResponse.class))),
        @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: 요청 형식·필드 값을 확인하세요. error.details.fields가 있으면 field와 reason으로 입력 오류를 표시합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "VALIDATION_FAILED", value = "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"요청 값이 올바르지 않습니다.\"}}")})),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 액세스 토큰이 없거나 만료되었습니다. refresh 성공 후 새 토큰으로 재시도하고, refresh도 401이면 로그인 화면으로 이동합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "UNAUTHENTICATED", value = "{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"로그인이 필요합니다.\"}}")})),
        @ApiResponse(responseCode = "404", description = "NOT_FOUND: 삭제되었거나 현재 사용자의 소유가 아닌 ID입니다. 타인의 데이터 존재 여부는 공개하지 않습니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "NOT_FOUND", value = "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"삭제된 주제입니다.\"}}")})),
        @ApiResponse(responseCode = "409", description = "TOPIC_NAME_TAKEN: 다른 주제에서 사용하는 이름입니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "TOPIC_NAME_TAKEN", value = "{\"error\":{\"code\":\"TOPIC_NAME_TAKEN\",\"message\":\"같은 이름의 주제가 이미 있습니다.\"}}")}))
    })
    @PatchMapping("/api/topics/{id}")
    public TopicResponse rename(@AuthenticationPrincipal Jwt jwt,@Parameter(description = "현재 사용자 소유의 UUID. 없거나 타인 소유이면 404입니다.") @PathVariable UUID id,@RequestBody TopicNameRequest request) {
        return topics.rename(UUID.fromString(jwt.getSubject()),id,request.name());
    }
    @Operation(summary = "주제 이동(부모 변경)",
            description = "주제를 parentId 아래로 옮깁니다. null이면 최상위로 옮깁니다. 하위 주제와 노트는 함께 따라갑니다. 새 부모의 마지막 순서로 들어가고 원래 부모의 순서는 0부터 다시 매깁니다. 같은 부모로 보내면 아무것도 바꾸지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "처리 성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = TopicResponse.class))),
        @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: 자기 자신이나 자기 하위 주제를 부모로 지정했습니다(details.fields[0].reason=cycle).",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "VALIDATION_FAILED", value = "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"자기 자신이나 하위 주제 아래로 옮길 수 없습니다.\",\"details\":{\"fields\":[{\"field\":\"parentId\",\"reason\":\"cycle\"}]}}}")})),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED: 액세스 토큰이 없거나 만료되었습니다. refresh 성공 후 새 토큰으로 재시도하고, refresh도 401이면 로그인 화면으로 이동합니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "UNAUTHENTICATED", value = "{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"로그인이 필요합니다.\"}}")})),
        @ApiResponse(responseCode = "404", description = "NOT_FOUND: 주제나 parentId가 삭제되었거나 현재 사용자의 소유가 아닙니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "NOT_FOUND", value = "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"삭제된 주제입니다.\"}}")})),
        @ApiResponse(responseCode = "409", description = "TOPIC_NAME_TAKEN: 새 부모 아래에 같은 이름의 주제가 있습니다.",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = {@ExampleObject(name = "TOPIC_NAME_TAKEN", value = "{\"error\":{\"code\":\"TOPIC_NAME_TAKEN\",\"message\":\"같은 이름의 주제가 이미 있습니다.\"}}")}))
    })
    @PutMapping("/api/topics/{id}/parent")
    public TopicResponse move(@AuthenticationPrincipal Jwt jwt,@Parameter(description = "현재 사용자 소유의 UUID. 없거나 타인 소유이면 404입니다.") @PathVariable UUID id,@RequestBody TopicMoveRequest request) {
        return topics.move(UUID.fromString(jwt.getSubject()),id,request.parentId());
    }
}
