package com.example.capstone.mcp;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** M5 정리 도구(create_topic, move_notes). 설계안 2.3·2.6·2.8(I4·I8·P3). */
@SpringBootTest(properties = {"capstone.mcp.enabled=true", "capstone.mcp.write-enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class McpOrganizeToolsIntegrationTest extends McpIntegrationSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15");

    private JsonNode tool(String name) throws Exception {
        for (JsonNode tool : rpc(bearer, "tools/list", Map.of()).path("result").path("tools")) {
            if (tool.path("name").asString().equals(name)) return tool;
        }
        throw new AssertionError(name);
    }

    private static Map<String, Object> move(Object topicId, UUID... ids) {
        Map<String, Object> args = new HashMap<>();
        args.put("noteIds", Arrays.stream(ids).map(UUID::toString).toList());
        args.put("topicId", topicId == null ? null : topicId.toString());
        return args;
    }

    private int version(UUID id, String token) throws Exception {
        return json(call("GET", "/api/notes/" + id, null, token)).path("version").asInt();
    }

    @Test
    void toolsMatchDesignContract() throws Exception {
        JsonNode create = tool("create_topic");
        assertThat(create.path("description").asString())
                .isEqualTo("새 주제(노트 폴더)를 만든다. 같은 이름이 있으면 실패한다. 주제는 한 단계뿐이며 하위 주제는 없다.");
        assertThat(create.path("inputSchema").path("properties").path("name").path("maxLength").asInt()).isEqualTo(50);
        assertThat(create.path("inputSchema").path("required").toString()).isEqualTo("[\"name\"]");
        assertThat(create.path("annotations").path("readOnlyHint").asBoolean()).isFalse();
        assertThat(create.path("annotations").path("destructiveHint").asBoolean()).isFalse();
        assertThat(create.path("annotations").path("idempotentHint").asBoolean()).isFalse();
        assertThat(create.path("annotations").path("openWorldHint").asBoolean()).isFalse();

        JsonNode move = tool("move_notes");
        assertThat(move.path("description").asString()).isEqualTo("노트 여러 개를 한 주제로 옮긴다. topicId를 null로 주면 미분류로 "
                + "옮긴다. 노트 내용과 version은 바뀌지 않고 추천도 그대로다. 옮기기 전에 어떤 노트를 어디로 옮길지 사용자에게 보여 주고 "
                + "확인받는다. 일부만 실패할 수 있으니 결과의 failed를 확인한다.");
        JsonNode noteIds = move.path("inputSchema").path("properties").path("noteIds");
        assertThat(noteIds.path("minItems").asInt()).isEqualTo(1);
        assertThat(noteIds.path("maxItems").asInt()).isEqualTo(50);
        assertThat(noteIds.path("uniqueItems").asBoolean()).isTrue();
        assertThat(move.path("inputSchema").path("required").toString()).isEqualTo("[\"noteIds\",\"topicId\"]");
        assertThat(move.path("annotations").path("destructiveHint").asBoolean()).isFalse();
        assertThat(move.path("annotations").path("idempotentHint").asBoolean()).isTrue();
    }

    @Test
    void createsTopicAndMapsNameErrors() throws Exception {
        JsonNode created = callTool("create_topic", Map.of("name", "알고리즘·자료구조")).path("structuredContent");
        assertThat(created.path("name").asString()).isEqualTo("알고리즘·자료구조");
        assertThat(created.path("noteCount").asInt()).isZero();
        assertThat(created.path("sortOrder").asInt()).isZero();

        JsonNode duplicate = callTool("create_topic", Map.of("name", "알고리즘·자료구조"));
        assertThat(duplicate.path("isError").asBoolean()).isTrue();
        JsonNode error = duplicate.path("structuredContent").path("error");
        assertThat(error.path("code").asString()).isEqualTo("TOPIC_NAME_TAKEN");
        assertThat(error.path("retryable").asBoolean()).isFalse();
        assertThat(error.path("nextStep").asString()).contains("list_topics");

        for (String name : List.of("상위/하위", "😀".repeat(51), " ")) {
            JsonNode invalid = callTool("create_topic", Map.of("name", name)).path("structuredContent").path("error");
            assertThat(invalid.path("code").asString()).isEqualTo("VALIDATION_FAILED");
            assertThat(invalid.path("details").path("fields").get(0).path("field").asString()).isEqualTo("name");
        }
    }

    @Test
    void movesPerNoteAndKeepsVersions() throws Exception {
        UUID target = topic("네트워크");
        UUID first = note("TCP", null, "본문 1");
        UUID second = note("UDP", null, "본문 2");
        call("PUT", "/api/notes/" + second, mapper.writeValueAsString(Map.of("title", "UDP", "body", "고친 본문 2", "version", 0)));
        UUID clash = note("겹치는 제목", null, "본문 3");
        note("겹치는 제목", target, "이미 있는 노트");
        UUID missing = UUID.randomUUID();

        JsonNode result = callTool("move_notes", move(target, first, clash, second, missing));
        assertThat(result.path("isError").asBoolean()).isFalse();
        JsonNode body = result.path("structuredContent");
        assertThat(body.path("moved")).hasSize(2);
        assertThat(body.path("moved").get(0).path("id").asString()).isEqualTo(first.toString());
        assertThat(body.path("moved").get(0).path("title").asString()).isEqualTo("TCP");
        assertThat(body.path("moved").get(0).path("topicId").asString()).isEqualTo(target.toString());
        assertThat(body.path("moved").get(1).path("id").asString()).isEqualTo(second.toString());
        assertThat(body.path("failed")).hasSize(2);
        assertThat(body.path("failed").get(0).path("noteId").asString()).isEqualTo(clash.toString());
        assertThat(body.path("failed").get(0).path("code").asString()).isEqualTo("NOTE_TITLE_TAKEN");
        assertThat(body.path("failed").get(1).path("noteId").asString()).isEqualTo(missing.toString());
        assertThat(body.path("failed").get(1).path("code").asString()).isEqualTo("NOT_FOUND");

        assertThat(version(first, bearer)).isZero();
        assertThat(version(second, bearer)).isEqualTo(1);
        assertThat(json(call("GET", "/api/notes/" + second, null)).path("body").asString()).isEqualTo("고친 본문 2");
        assertThat(json(call("GET", "/api/notes/" + clash, null)).path("topicId").isNull()).isTrue();

        JsonNode backResult = callTool("move_notes", move(null, first));
        JsonNode back = backResult.path("structuredContent");
        assertThat(back.path("moved")).as(backResult.toString()).hasSize(1);
        assertThat(back.path("moved").get(0).path("topicId").isNull()).isTrue();
        assertThat(version(first, bearer)).isZero();
    }

    @Test
    void otherUsersNotesAndTopicsAreNotFoundPerNote(CapturedOutput output) throws Exception {
        UUID mine = note("내 노트", null, "본문");
        UUID mineTopic = topic("내 주제");
        UUID theirNote = UUID.fromString(json(call("POST", "/api/notes", "{\"title\":\"타인 노트 제목\",\"body\":\"x\"}", otherBearer))
                .path("id").asString());
        UUID theirTopic = UUID.fromString(json(call("POST", "/api/topics", "{\"name\":\"타인 주제\"}", otherBearer))
                .path("id").asString());

        JsonNode mixed = callTool("move_notes", move(mineTopic, mine, theirNote)).path("structuredContent");
        assertThat(mixed.path("moved")).hasSize(1);
        assertThat(mixed.path("failed").get(0).path("noteId").asString()).isEqualTo(theirNote.toString());
        assertThat(mixed.path("failed").get(0).path("code").asString()).isEqualTo("NOT_FOUND");
        JsonNode missingNote = callTool("move_notes", move(mineTopic, UUID.randomUUID())).path("structuredContent");
        assertThat(mixed.path("failed").get(0).path("message")).isEqualTo(missingNote.path("failed").get(0).path("message"));
        assertThat(json(call("GET", "/api/notes/" + theirNote, null, otherBearer)).path("topicId").isNull()).isTrue();

        JsonNode toTheirs = callTool("move_notes", move(theirTopic, mine));
        JsonNode toMissing = callTool("move_notes", move(UUID.randomUUID(), mine));
        assertThat(toTheirs.path("isError").asBoolean()).isTrue();
        assertThat(toTheirs.path("structuredContent").path("moved")).isEmpty();
        assertThat(toTheirs.path("structuredContent").path("failed").get(0).path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(toTheirs.path("structuredContent")).isEqualTo(toMissing.path("structuredContent"));
        assertThat(json(call("GET", "/api/notes?topicId=" + theirTopic, null, otherBearer)).path("notes")).isEmpty();
        assertThat(json(call("GET", "/api/notes/" + mine, null)).path("topicId").asString()).isEqualTo(mineTopic.toString());

        assertThat(output.getAll()).doesNotContain("타인 노트 제목").doesNotContain("내 노트").doesNotContain(bearer);
    }

    @Test
    void rejectsInvalidMoveRequests() throws Exception {
        UUID one = note("하나", null, "");
        UUID[] tooMany = new UUID[51];
        for (int i = 0; i < tooMany.length; i++) tooMany[i] = UUID.randomUUID();
        Map<String, Object> missingTopic = new HashMap<>(Map.of("noteIds", List.of(one.toString())));
        List<Map<String, Object>> cases = List.of(move(null, tooMany), move(null), move(null, one, one),
                Map.of("noteIds", List.of("bad"), "topicId", "none"), missingTopic);
        for (Map<String, Object> args : cases) {
            JsonNode error = callTool("move_notes", args).path("structuredContent").path("error");
            assertThat(error.path("code").asString()).as(args.toString()).isEqualTo("VALIDATION_FAILED");
        }
        assertThat(json(call("GET", "/api/notes/" + one, null)).path("topicId").isNull()).isTrue();
    }
}
