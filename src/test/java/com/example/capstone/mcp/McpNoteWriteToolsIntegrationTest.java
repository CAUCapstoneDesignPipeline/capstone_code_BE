package com.example.capstone.mcp;

import java.util.ArrayList;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** M4 노트 작성·수정·제목 변경 도구. 설계안 2.3·2.6·2.8(I4·I7). */
@SpringBootTest(properties = {"capstone.mcp.enabled=true", "capstone.mcp.write-enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class McpNoteWriteToolsIntegrationTest extends McpIntegrationSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15");

    private JsonNode tool(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (tool.path("name").asString().equals(name)) return tool;
        }
        throw new AssertionError(name);
    }

    @Test
    void writeToolsAreListedWithDesignContract() throws Exception {
        JsonNode tools = rpc(bearer, "tools/list", Map.of()).path("result").path("tools");
        List<String> names = new ArrayList<>();
        tools.forEach(tool -> names.add(tool.path("name").asString()));
        assertThat(names).containsExactly("list_topics", "search_notes", "get_note",
                "create_note", "update_note", "rename_note", "create_topic", "move_notes");

        JsonNode create = tool(tools, "create_note");
        assertThat(create.path("description").asString()).isEqualTo("새 노트를 만든다. 사용자가 저장을 요청했을 때만 쓴다. "
                + "같은 주제에 같은 제목이 있으면 실패한다. 추천 분석은 자동으로 시작하지 않는다.");
        assertThat(create.path("inputSchema").path("required").toString()).isEqualTo("[\"title\"]");
        assertThat(create.path("inputSchema").path("properties").path("title").path("maxLength").asInt()).isEqualTo(200);
        assertThat(create.path("inputSchema").path("properties").path("body").path("maxLength").asInt()).isEqualTo(1000000);
        assertHints(create, false, false, false);

        JsonNode update = tool(tools, "update_note");
        assertThat(update.path("description").asString()).isEqualTo("노트의 제목과 본문 전체를 바꾼다. 일부만 고칠 때도 고친 본문 "
                + "전체를 보내야 하므로, get_note로 truncated가 false가 될 때까지 끝까지 읽은 뒤에만 쓴다. NOTE_CONFLICT가 나면 "
                + "자동으로 다시 시도하지 말고 사용자에게 알린다. 제목만 바꿀 때는 rename_note를 쓴다.");
        assertThat(update.path("inputSchema").path("required").toString())
                .isEqualTo("[\"noteId\",\"title\",\"body\",\"version\"]");
        assertHints(update, false, true, false);

        JsonNode rename = tool(tools, "rename_note");
        assertThat(rename.path("description").asString()).isEqualTo("노트 제목만 바꾼다. 본문은 그대로다. 제목을 바꾸면 이 노트에 "
                + "걸린 기존 추천이 최신이 아닌 것으로 표시될 수 있으니, 여러 노트의 제목을 한꺼번에 바꾸기 전에 사용자에게 알리고 확인받는다.");
        assertThat(rename.path("inputSchema").path("required").toString()).isEqualTo("[\"noteId\",\"title\",\"version\"]");
        assertHints(rename, false, false, false);
    }

    private static void assertHints(JsonNode tool, boolean readOnly, boolean destructive, boolean idempotent) {
        JsonNode hints = tool.path("annotations");
        assertThat(hints.path("readOnlyHint").asBoolean()).isEqualTo(readOnly);
        assertThat(hints.path("destructiveHint").asBoolean()).isEqualTo(destructive);
        assertThat(hints.path("idempotentHint").asBoolean()).isEqualTo(idempotent);
        assertThat(hints.path("openWorldHint").asBoolean()).isFalse();
    }

    @Test
    void createsNoteWithoutEchoingBody() throws Exception {
        UUID topic = topic("네트워크");
        JsonNode created = callTool("create_note", Map.of("title", "복습", "body", "TCP 세 단계 연결 😀", "topicId", topic.toString()));
        assertThat(created.path("isError").asBoolean()).isFalse();
        JsonNode note = created.path("structuredContent");
        assertThat(note.path("version").asInt()).isZero();
        assertThat(note.path("bodyChars").asInt()).isEqualTo(13);
        assertThat(note.path("topicId").asString()).isEqualTo(topic.toString());
        assertThat(note.has("body")).isFalse();
        call("GET", "/api/notes/" + note.path("id").asString(), null).andExpect(status().isOk());

        JsonNode unassigned = callTool("create_note", Map.of("title", "빈 노트")).path("structuredContent");
        assertThat(unassigned.path("topicId").isNull()).isTrue();
        assertThat(unassigned.path("bodyChars").asInt()).isZero();

        JsonNode duplicate = callTool("create_note", Map.of("title", "복습", "topicId", topic.toString()));
        JsonNode error = duplicate.path("structuredContent").path("error");
        assertThat(error.path("code").asString()).isEqualTo("NOTE_TITLE_TAKEN");
        assertThat(error.path("retryable").asBoolean()).isTrue();
        assertThat(error.path("details").path("titles").get(0).asString()).isEqualTo("복습");

        JsonNode slash = callTool("create_note", Map.of("title", "a/b"));
        assertThat(slash.path("structuredContent").path("error").path("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(slash.path("structuredContent").path("error").path("details").path("fields").get(0).path("reason").asString())
                .isEqualTo("contains_slash");
        JsonNode tooLong = callTool("create_note", Map.of("title", "😀".repeat(201)));
        assertThat(tooLong.path("structuredContent").path("error").path("details").path("fields").get(0).path("reason").asString())
                .isEqualTo("too_long");
    }

    @Test
    void updatesAndRejectsStaleVersionWithoutBody() throws Exception {
        UUID id = note("수정 대상", null, "처음 본문");
        JsonNode updated = callTool("update_note", Map.of("noteId", id.toString(), "title", "수정 대상",
                "body", "고친 본문", "version", 0)).path("structuredContent");
        assertThat(updated.path("version").asInt()).isEqualTo(1);
        assertThat(updated.path("bodyChars").asInt()).isEqualTo(5);
        assertThat(updated.has("body")).isFalse();

        JsonNode stale = callTool("update_note", Map.of("noteId", id.toString(), "title", "수정 대상",
                "body", "덮어쓰려는 본문", "version", 0));
        assertConflictWithoutBody(stale, 1, "고친 본문");
        assertThat(json(call("GET", "/api/notes/" + id, null)).path("body").asString()).isEqualTo("고친 본문");
    }

    @Test
    void renameKeepsBodyAndRejectsStaleVersion() throws Exception {
        String body = "  앞뒤 공백과 줄바꿈\n\n그리고 이모지 👩‍💻 é  ";
        UUID id = note("운영체제 334d0523765980c", null, body);
        String before = json(call("GET", "/api/notes/" + id, null)).path("body").asString();

        JsonNode renamed = callTool("rename_note", Map.of("noteId", id.toString(), "title", "운영체제", "version", 0));
        assertThat(renamed.path("isError").asBoolean()).isFalse();
        assertThat(renamed.path("structuredContent").path("title").asString()).isEqualTo("운영체제");
        assertThat(renamed.path("structuredContent").path("version").asInt()).isEqualTo(1);
        assertThat(renamed.path("structuredContent").has("body")).isFalse();
        JsonNode after = json(call("GET", "/api/notes/" + id, null));
        assertThat(after.path("body").asString()).isEqualTo(before);
        assertThat(after.path("title").asString()).isEqualTo("운영체제");

        call("PUT", "/api/notes/" + id, mapper.writeValueAsString(Map.of("title", "운영체제", "body", "앱에서 고친 본문", "version", 1)))
                .andExpect(status().isOk());
        assertConflictWithoutBody(callTool("rename_note", Map.of("noteId", id.toString(), "title", "옛 버전 제목", "version", 1)),
                2, "앱에서 고친 본문");
        assertConflictWithoutBody(callTool("rename_note", Map.of("noteId", id.toString(), "title", "앞선 버전 제목", "version", 3)),
                2, "앱에서 고친 본문");
        assertThat(json(call("GET", "/api/notes/" + id, null)).path("title").asString()).isEqualTo("운영체제");
    }

    private void assertConflictWithoutBody(JsonNode result, int currentVersion, String currentBody) {
        assertThat(result.path("isError").asBoolean()).isTrue();
        JsonNode error = result.path("structuredContent").path("error");
        assertThat(error.path("code").asString()).isEqualTo("NOTE_CONFLICT");
        assertThat(error.path("message").asString()).isEqualTo("다른 곳에서 이 노트가 먼저 수정되었습니다.");
        assertThat(error.path("retryable").asBoolean()).isFalse();
        assertThat(error.path("details").path("currentVersion").asInt()).isEqualTo(currentVersion);
        assertThat(error.path("details").path("currentUpdatedAt").asString()).endsWith("Z");
        assertThat(error.path("details").has("current")).isFalse();
        assertThat(result.toString()).doesNotContain(currentBody);
    }

    @Test
    void otherUsersNotesAndTopicsCannotBeWrittenAndLookMissing(CapturedOutput output) throws Exception {
        UUID theirNote = UUID.fromString(json(call("POST", "/api/notes",
                "{\"title\":\"타인 노트 제목\",\"body\":\"타인 본문 내용\"}", otherBearer)).path("id").asString());
        UUID theirTopic = UUID.fromString(json(call("POST", "/api/topics", "{\"name\":\"타인 주제\"}", otherBearer))
                .path("id").asString());
        String missing = UUID.randomUUID().toString();

        assertSameNotFound(callTool("update_note", Map.of("noteId", theirNote.toString(), "title", "탈취", "body", "", "version", 0)),
                callTool("update_note", Map.of("noteId", missing, "title", "탈취", "body", "", "version", 0)));
        assertSameNotFound(callTool("rename_note", Map.of("noteId", theirNote.toString(), "title", "탈취", "version", 0)),
                callTool("rename_note", Map.of("noteId", missing, "title", "탈취", "version", 0)));
        assertSameNotFound(callTool("create_note", Map.of("title", "남의 주제에", "topicId", theirTopic.toString())),
                callTool("create_note", Map.of("title", "남의 주제에", "topicId", missing)));

        JsonNode theirs = json(call("GET", "/api/notes/" + theirNote, null, otherBearer));
        assertThat(theirs.path("title").asString()).isEqualTo("타인 노트 제목");
        assertThat(theirs.path("version").asInt()).isZero();
        assertThat(json(call("GET", "/api/notes?topicId=" + theirTopic, null, otherBearer)).path("notes")).isEmpty();

        callTool("create_note", Map.of("title", "로그확인제목", "body", "로그확인본문"));
        assertThat(output.getAll()).doesNotContain("로그확인제목").doesNotContain("로그확인본문")
                .doesNotContain("타인 본문 내용").doesNotContain(bearer);
    }

    private static void assertSameNotFound(JsonNode foreign, JsonNode missing) {
        assertThat(foreign.path("structuredContent").path("error").path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(foreign.path("structuredContent")).isEqualTo(missing.path("structuredContent"));
    }
}
