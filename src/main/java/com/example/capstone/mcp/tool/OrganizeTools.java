package com.example.capstone.mcp.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;

import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;
import com.example.capstone.mcp.config.McpProperties;
import com.example.capstone.note.dto.response.NoteResponse;
import com.example.capstone.note.service.NoteService;
import com.example.capstone.topic.service.TopicService;

/** 노트 정리 도구: 주제 만들기, 노트 여러 건 옮기기. */
@Component
@ConditionalOnBooleanProperty("capstone.mcp.enabled")
public class OrganizeTools {

    private static final Logger log = LoggerFactory.getLogger(OrganizeTools.class);

    private final TopicService topics;
    private final NoteService notes;
    private final McpToolSupport support;
    private final McpProperties.Limits limits;

    public OrganizeTools(TopicService topics, NoteService notes, McpToolSupport support, McpProperties properties) {
        this.topics = topics;
        this.notes = notes;
        this.support = support;
        this.limits = properties.limits();
    }

    public SyncToolSpecification createTopic() {
        String input = """
                {"type":"object","properties":{
                  "name":{"type":"string","minLength":1,"maxLength":50,"pattern":"^[^/]*$"}},
                 "required":["name"],"additionalProperties":false}
                """;
        String output = """
                {"type":"object","properties":{
                  "id":{"type":"string","format":"uuid"},
                  "name":{"type":"string"},
                  "sortOrder":{"type":"integer"},
                  "noteCount":{"type":"integer"},
                  "createdAt":{"type":"string","format":"date-time"}},
                 "required":["id","name","sortOrder","noteCount","createdAt"]}
                """;
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("create_topic")
                .description("새 주제(노트 폴더)를 만든다. 같은 이름이 있으면 실패한다. 주제는 한 단계뿐이며 하위 주제는 없다.")
                .inputSchema(support.schema(input))
                .outputSchema(support.schema(output))
                .annotations(McpToolSupport.hints(false, false, false))
                .build();
        return support.tool(tool, call -> topics.create(call.userId(), call.arguments().requiredString("name")));
    }

    public SyncToolSpecification moveNotes() {
        String input = """
                {"type":"object","properties":{
                  "noteIds":{"type":"array","items":{"type":"string","format":"uuid"},
                             "minItems":1,"maxItems":%d,"uniqueItems":true},
                  "topicId":{"type":["string","null"],"format":"uuid"}},
                 "required":["noteIds","topicId"],"additionalProperties":false}
                """.formatted(limits.moveMaxNotes());
        String output = """
                {"type":"object","properties":{
                  "moved":{"type":"array","items":{"type":"object","properties":{
                    "id":{"type":"string","format":"uuid"},
                    "title":{"type":"string"},
                    "topicId":{"type":["string","null"],"format":"uuid"}},
                    "required":["id","title","topicId"]}},
                  "failed":{"type":"array","items":{"type":"object","properties":{
                    "noteId":{"type":"string","format":"uuid"},
                    "code":{"type":"string"},
                    "message":{"type":"string"}},
                    "required":["noteId","code","message"]}}},
                 "required":["moved","failed"]}
                """;
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("move_notes")
                .description("노트 여러 개를 한 주제로 옮긴다. topicId를 null로 주면 미분류로 옮긴다. "
                        + "노트 내용과 version은 바뀌지 않고 추천도 그대로다. 옮기기 전에 어떤 노트를 어디로 옮길지 "
                        + "사용자에게 보여 주고 확인받는다. 일부만 실패할 수 있으니 결과의 failed를 확인한다.")
                .inputSchema(support.schema(input))
                .outputSchema(support.schema(output))
                .annotations(McpToolSupport.hints(false, false, true))
                .build();
        return support.tool(tool, call -> {
            McpArguments args = call.arguments();
            List<UUID> noteIds = args.requiredUuidList("noteIds", 1, limits.moveMaxNotes());
            UUID topicId = args.nullableUuid("topicId");
            List<Moved> moved = new ArrayList<>();
            List<Failed> failed = new ArrayList<>();
            // 노트마다 서비스 트랜잭션 하나다. 한 건의 실패가 앞서 옮긴 노트를 되돌리지 않는다(설계안 Q9 권장안).
            for (UUID noteId : noteIds) {
                try {
                    NoteResponse note = notes.move(call.userId(), noteId, topicId);
                    moved.add(new Moved(note.id(), note.title(), note.topicId()));
                } catch (ApiException exception) {
                    failed.add(new Failed(noteId, exception.errorCode().name(), exception.getMessage()));
                } catch (RuntimeException exception) {
                    log.warn("MCP move_notes item failed: {}", exception.getClass().getName());
                    failed.add(new Failed(noteId, ErrorCode.INTERNAL.name(), "일시적인 오류가 발생했습니다. 잠시 후 다시 시도하세요."));
                }
            }
            Result result = new Result(moved, failed);
            return moved.isEmpty() ? new McpToolSupport.Failure(result) : result;
        });
    }

    public record Moved(UUID id, String title, UUID topicId) {
    }

    public record Failed(UUID noteId, String code, String message) {
    }

    public record Result(List<Moved> moved, List<Failed> failed) {
    }
}
