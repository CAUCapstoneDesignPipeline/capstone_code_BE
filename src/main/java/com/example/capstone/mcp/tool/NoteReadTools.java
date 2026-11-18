package com.example.capstone.mcp.tool;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;

import com.example.capstone.mcp.config.McpProperties;
import com.example.capstone.note.dto.response.NoteResponse;
import com.example.capstone.note.dto.response.NoteSummaryResponse;
import com.example.capstone.note.service.NoteService;

@Component
@ConditionalOnBooleanProperty("capstone.mcp.enabled")
public class NoteReadTools {

    private static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    private final NoteService notes;
    private final McpToolSupport support;
    private final McpProperties.Limits limits;

    public NoteReadTools(NoteService notes, McpToolSupport support, McpProperties properties) {
        this.notes = notes;
        this.support = support;
        this.limits = properties.limits();
    }

    public SyncToolSpecification searchNotes() {
        String input = """
                {"type":"object","properties":{
                  "query":{"type":"string","maxLength":%d},
                  "topicId":{"type":"string","pattern":"^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|none)$"},
                  "sort":{"type":"string","enum":["title","updated"],"default":"updated"},
                  "limit":{"type":"integer","minimum":1,"maximum":%d,"default":%d},
                  "cursor":{"type":"string"}},
                 "additionalProperties":false}
                """.formatted(limits.searchQueryMaxChars(), limits.searchMaxLimit(), limits.searchDefaultLimit());
        String output = """
                {"type":"object","properties":{
                  "notes":{"type":"array","items":{"type":"object","properties":{
                    "id":{"type":"string","format":"uuid"},
                    "topicId":{"type":["string","null"],"format":"uuid"},
                    "title":{"type":"string"},
                    "snippet":{"type":"string"},
                    "version":{"type":"integer"},
                    "updatedAt":{"type":"string","format":"date-time"}},
                    "required":["id","topicId","title","snippet","version","updatedAt"]}},
                  "total":{"type":"integer"},
                  "nextCursor":{"type":["string","null"]}},
                 "required":["notes","total","nextCursor"]}
                """;
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("search_notes")
                .description("사용자의 노트를 제목·본문 부분 일치로 찾거나 주제별로 나열한다. "
                        + "본문 전체가 아니라 80자 미리보기만 준다. 본문이 필요하면 get_note를 부른다. "
                        + "의미 검색이 아니므로 결과가 없으면 다른 낱말로 다시 찾는다. "
                        + "미분류 노트는 topicId에 none을 넣는다.")
                .inputSchema(support.schema(input))
                .outputSchema(support.schema(output))
                .annotations(McpToolSupport.hints(true, false, true))
                .build();
        return support.tool(tool, call -> {
            McpArguments args = call.arguments();
            String query = args.optionalString("query", limits.searchQueryMaxChars());
            String topic = args.optionalString("topicId", 36);
            if (topic != null && !topic.equals("none") && !topic.matches(UUID_PATTERN)) {
                throw McpArguments.invalid("topicId", "invalid");
            }
            String sort = args.optionalEnum("sort", List.of("title", "updated"), "updated");
            int limit = args.optionalInt("limit", 1, limits.searchMaxLimit(), limits.searchDefaultLimit());
            int offset = decodeCursor(args.optionalString("cursor", 64));
            List<NoteSummaryResponse> all = notes.list(call.userId(), topic, sort, query).notes();
            if (offset > all.size()) {
                throw McpArguments.invalid("cursor", "invalid");
            }
            int end = Math.min(all.size(), offset + limit);
            String next = end < all.size() ? encodeCursor(end) : null;
            return new SearchResult(all.subList(offset, end), all.size(), next);
        });
    }

    public SyncToolSpecification getNote() {
        String input = """
                {"type":"object","properties":{
                  "noteId":{"type":"string","format":"uuid"},
                  "offset":{"type":"integer","minimum":0,"default":0},
                  "maxChars":{"type":"integer","minimum":%d,"maximum":%d,"default":%d}},
                 "required":["noteId"],
                 "additionalProperties":false}
                """.formatted(limits.noteMinChars(), limits.noteMaxChars(), limits.noteDefaultChars());
        String output = """
                {"type":"object","properties":{
                  "id":{"type":"string","format":"uuid"},
                  "topicId":{"type":["string","null"],"format":"uuid"},
                  "title":{"type":"string"},
                  "body":{"type":"string"},
                  "version":{"type":"integer"},
                  "createdAt":{"type":"string","format":"date-time"},
                  "updatedAt":{"type":"string","format":"date-time"},
                  "totalChars":{"type":"integer"},
                  "offset":{"type":"integer"},
                  "truncated":{"type":"boolean"},
                  "nextOffset":{"type":["integer","null"]}},
                 "required":["id","topicId","title","body","version","createdAt","updatedAt",
                             "totalChars","offset","truncated","nextOffset"]}
                """;
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("get_note")
                .description("노트 한 건의 마크다운 원문을 돌려준다. 긴 노트는 잘려서 오며 truncated가 true면 "
                        + "nextOffset으로 이어서 읽는다. 수정하려면 여기서 받은 version이 필요하다.")
                .inputSchema(support.schema(input))
                .outputSchema(support.schema(output))
                .annotations(McpToolSupport.hints(true, false, true))
                .build();
        return support.tool(tool, call -> {
            McpArguments args = call.arguments();
            UUID noteId = args.requiredUuid("noteId");
            int offset = args.optionalInt("offset", 0, Integer.MAX_VALUE, 0);
            int maxChars = args.optionalInt("maxChars", limits.noteMinChars(), limits.noteMaxChars(),
                    limits.noteDefaultChars());
            NoteResponse note = notes.get(call.userId(), noteId);
            String body = note.body();
            int total = body.codePointCount(0, body.length());
            if (offset > total) {
                throw McpArguments.invalid("offset", "out_of_range");
            }
            int end = (int) Math.min(total, (long) offset + maxChars);
            String part = body.substring(body.offsetByCodePoints(0, offset), body.offsetByCodePoints(0, end));
            boolean truncated = end < total;
            return new NoteSlice(note.id(), note.topicId(), note.title(), part, note.version(), note.createdAt(),
                    note.updatedAt(), total, offset, truncated, truncated ? end : null);
        });
    }

    private static String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("o:" + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeCursor(String cursor) {
        if (cursor == null) {
            return 0;
        }
        try {
            String text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!text.matches("o:[0-9]{1,9}")) {
                throw new IllegalArgumentException();
            }
            return Integer.parseInt(text.substring(2));
        } catch (IllegalArgumentException exception) {
            throw McpArguments.invalid("cursor", "invalid");
        }
    }

    public record SearchResult(List<NoteSummaryResponse> notes, int total, String nextCursor) {
    }

    public record NoteSlice(UUID id, UUID topicId, String title, String body, int version, Instant createdAt,
            Instant updatedAt, int totalChars, int offset, boolean truncated, Integer nextOffset) {
    }
}
