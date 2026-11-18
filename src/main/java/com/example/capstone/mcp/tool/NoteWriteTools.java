package com.example.capstone.mcp.tool;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.ObjectMapper;

import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;
import com.example.capstone.note.dto.request.NoteCreateRequest;
import com.example.capstone.note.dto.request.NoteUpdateRequest;
import com.example.capstone.note.dto.response.NoteResponse;
import com.example.capstone.note.service.NoteService;

/** 노트 작성·수정·제목 변경 도구. 결과에는 본문을 싣지 않고 bodyChars만 준다. */
@Component
@ConditionalOnBooleanProperty("capstone.mcp.enabled")
public class NoteWriteTools {

    private static final String TITLE_SCHEMA = "{\"type\":\"string\",\"minLength\":1,\"maxLength\":200,\"pattern\":\"^[^/]*$\"}";
    private static final String BODY_SCHEMA = "{\"type\":\"string\",\"maxLength\":1000000}";
    private static final String UUID_SCHEMA = "{\"type\":\"string\",\"format\":\"uuid\"}";
    private static final String NOTE_CONFLICT_MESSAGE = "다른 곳에서 이 노트가 먼저 수정되었습니다.";

    private final NoteService notes;
    private final McpToolSupport support;
    private final ObjectMapper mapper;

    public NoteWriteTools(NoteService notes, McpToolSupport support, ObjectMapper mapper) {
        this.notes = notes;
        this.support = support;
        this.mapper = mapper;
    }

    public SyncToolSpecification createNote() {
        String input = """
                {"type":"object","properties":{"title":%s,"body":%s,"topicId":%s},
                 "required":["title"],"additionalProperties":false}
                """.formatted(TITLE_SCHEMA, BODY_SCHEMA.replace("}", ",\"default\":\"\"}"), UUID_SCHEMA);
        String output = """
                {"type":"object","properties":{
                  "id":{"type":"string","format":"uuid"},
                  "topicId":{"type":["string","null"],"format":"uuid"},
                  "title":{"type":"string"},
                  "version":{"type":"integer"},
                  "createdAt":{"type":"string","format":"date-time"},
                  "updatedAt":{"type":"string","format":"date-time"},
                  "bodyChars":{"type":"integer"}},
                 "required":["id","topicId","title","version","createdAt","updatedAt","bodyChars"]}
                """;
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("create_note")
                .description("새 노트를 만든다. 사용자가 저장을 요청했을 때만 쓴다. 같은 주제에 같은 제목이 있으면 실패한다. "
                        + "추천 분석은 자동으로 시작하지 않는다.")
                .inputSchema(support.schema(input))
                .outputSchema(support.schema(output))
                .annotations(McpToolSupport.hints(false, false, false))
                .build();
        return support.tool(tool, call -> {
            McpArguments args = call.arguments();
            String title = args.requiredString("title");
            String body = args.optionalString("body", Integer.MAX_VALUE);
            UUID topicId = args.has("topicId") ? args.nullableUuid("topicId") : null;
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("title", title);
            request.put("topicId", topicId);
            request.put("body", body == null ? "" : body);
            NoteResponse note = notes.create(call.userId(), mapper.convertValue(request, NoteCreateRequest.class));
            return new Created(note.id(), note.topicId(), note.title(), note.version(), note.createdAt(),
                    note.updatedAt(), chars(note.body()));
        });
    }

    public SyncToolSpecification updateNote() {
        String input = """
                {"type":"object","properties":{"noteId":%s,"title":%s,"body":%s,
                  "version":{"type":"integer","minimum":0}},
                 "required":["noteId","title","body","version"],"additionalProperties":false}
                """.formatted(UUID_SCHEMA, TITLE_SCHEMA, BODY_SCHEMA);
        String output = """
                {"type":"object","properties":{
                  "id":{"type":"string","format":"uuid"},
                  "topicId":{"type":["string","null"],"format":"uuid"},
                  "title":{"type":"string"},
                  "version":{"type":"integer"},
                  "updatedAt":{"type":"string","format":"date-time"},
                  "bodyChars":{"type":"integer"}},
                 "required":["id","topicId","title","version","updatedAt","bodyChars"]}
                """;
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("update_note")
                .description("노트의 제목과 본문 전체를 바꾼다. 일부만 고칠 때도 고친 본문 전체를 보내야 하므로, "
                        + "get_note로 truncated가 false가 될 때까지 끝까지 읽은 뒤에만 쓴다. "
                        + "NOTE_CONFLICT가 나면 자동으로 다시 시도하지 말고 사용자에게 알린다. 제목만 바꿀 때는 rename_note를 쓴다.")
                .inputSchema(support.schema(input))
                .outputSchema(support.schema(output))
                .annotations(McpToolSupport.hints(false, true, false))
                .build();
        return support.tool(tool, call -> {
            McpArguments args = call.arguments();
            UUID noteId = args.requiredUuid("noteId");
            String title = args.requiredString("title");
            String body = args.requiredString("body");
            int version = args.requiredInt("version", 0, Integer.MAX_VALUE);
            NoteResponse note = notes.update(call.userId(), noteId, new NoteUpdateRequest(title, body, version));
            return new Updated(note.id(), note.topicId(), note.title(), note.version(), note.updatedAt(),
                    chars(note.body()));
        });
    }

    public SyncToolSpecification renameNote() {
        String input = """
                {"type":"object","properties":{"noteId":%s,"title":%s,"version":{"type":"integer","minimum":0}},
                 "required":["noteId","title","version"],"additionalProperties":false}
                """.formatted(UUID_SCHEMA, TITLE_SCHEMA);
        String output = """
                {"type":"object","properties":{
                  "id":{"type":"string","format":"uuid"},
                  "topicId":{"type":["string","null"],"format":"uuid"},
                  "title":{"type":"string"},
                  "version":{"type":"integer"},
                  "updatedAt":{"type":"string","format":"date-time"}},
                 "required":["id","topicId","title","version","updatedAt"]}
                """;
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("rename_note")
                .description("노트 제목만 바꾼다. 본문은 그대로다. 제목을 바꾸면 이 노트에 걸린 기존 추천이 최신이 아닌 것으로 "
                        + "표시될 수 있으니, 여러 노트의 제목을 한꺼번에 바꾸기 전에 사용자에게 알리고 확인받는다.")
                .inputSchema(support.schema(input))
                .outputSchema(support.schema(output))
                .annotations(McpToolSupport.hints(false, false, false))
                .build();
        return support.tool(tool, call -> {
            McpArguments args = call.arguments();
            UUID noteId = args.requiredUuid("noteId");
            String title = args.requiredString("title");
            int version = args.requiredInt("version", 0, Integer.MAX_VALUE);
            NoteResponse current = notes.get(call.userId(), noteId);
            // 읽은 본문이 요청 version의 본문일 때만 저장한다. 더 새 version을 받았는데 옛 본문을 읽었다면
            // 그대로 저장하면 그 사이의 본문 수정을 덮어쓰므로 BE와 같은 NOTE_CONFLICT로 멈춘다.
            if (current.version() != version) {
                throw new ApiException(ErrorCode.NOTE_CONFLICT, NOTE_CONFLICT_MESSAGE, Map.of("current", current));
            }
            NoteResponse note = notes.update(call.userId(), noteId,
                    new NoteUpdateRequest(title, current.body(), version));
            return new Renamed(note.id(), note.topicId(), note.title(), note.version(), note.updatedAt());
        });
    }

    private static int chars(String body) {
        return body.codePointCount(0, body.length());
    }

    public record Created(UUID id, UUID topicId, String title, int version, Instant createdAt, Instant updatedAt,
            int bodyChars) {
    }

    public record Updated(UUID id, UUID topicId, String title, int version, Instant updatedAt, int bodyChars) {
    }

    public record Renamed(UUID id, UUID topicId, String title, int version, Instant updatedAt) {
    }
}
