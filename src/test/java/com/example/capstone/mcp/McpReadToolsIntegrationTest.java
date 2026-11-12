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

/** M3 읽기 도구(list_topics, search_notes, get_note)와 오류 변환. 설계안 2.3·2.6·2.8(I2~I6). */
@SpringBootTest(properties = "capstone.mcp.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class McpReadToolsIntegrationTest extends McpIntegrationSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15");

    @Test
    void toolListMatchesDesignContract() throws Exception {
        JsonNode tools = rpc(bearer, "tools/list", Map.of()).path("result").path("tools");
        List<String> names = new ArrayList<>();
        tools.forEach(tool -> names.add(tool.path("name").asString()));
        assertThat(names).containsExactly("list_topics", "search_notes", "get_note");

        JsonNode search = tools.get(1);
        assertThat(search.path("description").asString()).isEqualTo(
                "사용자의 노트를 제목·본문 부분 일치로 찾거나 주제별로 나열한다. 본문 전체가 아니라 80자 미리보기만 준다. "
                        + "본문이 필요하면 get_note를 부른다. 의미 검색이 아니므로 결과가 없으면 다른 낱말로 다시 찾는다. "
                        + "미분류 노트는 topicId에 none을 넣는다.");
        JsonNode props = search.path("inputSchema").path("properties");
        assertThat(props.path("query").path("maxLength").asInt()).isEqualTo(100);
        assertThat(props.path("sort").path("default").asString()).isEqualTo("updated");
        assertThat(props.path("limit").path("minimum").asInt()).isEqualTo(1);
        assertThat(props.path("limit").path("maximum").asInt()).isEqualTo(50);
        assertThat(props.path("limit").path("default").asInt()).isEqualTo(20);
        assertThat(search.path("inputSchema").has("required")).isFalse();

        JsonNode get = tools.get(2);
        assertThat(get.path("description").asString()).isEqualTo(
                "노트 한 건의 마크다운 원문을 돌려준다. 긴 노트는 잘려서 오며 truncated가 true면 nextOffset으로 이어서 읽는다. "
                        + "수정하려면 여기서 받은 version이 필요하다.");
        JsonNode getProps = get.path("inputSchema").path("properties");
        assertThat(getProps.path("maxChars").path("minimum").asInt()).isEqualTo(1000);
        assertThat(getProps.path("maxChars").path("maximum").asInt()).isEqualTo(30000);
        assertThat(getProps.path("maxChars").path("default").asInt()).isEqualTo(10000);
        assertThat(getProps.path("offset").path("minimum").asInt()).isEqualTo(0);
        assertThat(get.path("inputSchema").path("required").toString()).isEqualTo("[\"noteId\"]");

        for (JsonNode tool : tools) {
            JsonNode hints = tool.path("annotations");
            assertThat(hints.path("readOnlyHint").asBoolean()).isTrue();
            assertThat(hints.path("destructiveHint").asBoolean()).isFalse();
            assertThat(hints.path("idempotentHint").asBoolean()).isTrue();
            assertThat(hints.path("openWorldHint").asBoolean()).isFalse();
            assertThat(tool.has("outputSchema")).isTrue();
        }
    }

    @Test
    void searchesFiltersAndPagesOnlyOwnNotes() throws Exception {
        UUID os = topic("운영체제");
        note("페이징 정리", os, "가상 메모리와 페이징은 주소 변환을 다룬다.");
        note("스케줄링", os, "라운드 로빈 스케줄링 예시");
        note("미분류 메모", null, "페이징 교체 알고리즘 메모");
        call("POST", "/api/notes", "{\"title\":\"타인 페이징\",\"body\":\"페이징\"}", otherBearer);

        JsonNode found = callTool("search_notes", Map.of("query", "페이징")).path("structuredContent");
        assertThat(found.path("total").asInt()).isEqualTo(2);
        assertThat(found.path("notes").toString()).doesNotContain("타인");
        assertThat(found.path("nextCursor").isNull()).isTrue();

        JsonNode unassigned = callTool("search_notes", Map.of("topicId", "none")).path("structuredContent");
        assertThat(unassigned.path("total").asInt()).isEqualTo(1);
        JsonNode first = unassigned.path("notes").get(0);
        assertThat(first.has("topicId")).isTrue();
        assertThat(first.path("topicId").isNull()).isTrue();

        JsonNode page1 = callTool("search_notes", Map.of("limit", 2)).path("structuredContent");
        assertThat(page1.path("notes")).hasSize(2);
        assertThat(page1.path("total").asInt()).isEqualTo(3);
        assertThat(page1.path("notes").get(0).path("title").asString()).isEqualTo("미분류 메모");
        String cursor = page1.path("nextCursor").asString();
        JsonNode page2 = callTool("search_notes", Map.of("limit", 2, "cursor", cursor)).path("structuredContent");
        assertThat(page2.path("notes")).hasSize(1);
        assertThat(page2.path("nextCursor").isNull()).isTrue();

        JsonNode byTitle = callTool("search_notes", Map.of("topicId", os.toString(), "sort", "title"))
                .path("structuredContent");
        assertThat(byTitle.path("notes").get(0).path("title").asString()).isEqualTo("스케줄링");
    }

    @Test
    void readsLongNoteInPiecesThatReassembleExactly() throws Exception {
        StringBuilder text = new StringBuilder();
        String[] parts = {"한글 문장입니다. ", "😀", "👩‍💻", "é", "가나다라마바사 ", "𝒳", "\n"};
        for (int i = 0; text.codePointCount(0, text.length()) < 2600; i++) {
            text.append(parts[i % parts.length]);
        }
        String body = java.text.Normalizer.normalize(text.toString(), java.text.Normalizer.Form.NFC);
        UUID id = note("긴 노트", null, body);
        int total = body.codePointCount(0, body.length());

        StringBuilder joined = new StringBuilder();
        Integer offset = 0;
        int calls = 0;
        while (offset != null) {
            JsonNode slice = callTool("get_note", Map.of("noteId", id.toString(), "offset", offset, "maxChars", 1000))
                    .path("structuredContent");
            assertThat(slice.path("totalChars").asInt()).isEqualTo(total);
            assertThat(slice.path("offset").asInt()).isEqualTo(offset);
            String piece = slice.path("body").asString();
            assertThat(piece.codePointCount(0, piece.length())).isLessThanOrEqualTo(1000);
            joined.append(piece);
            offset = slice.path("truncated").asBoolean() ? slice.path("nextOffset").asInt() : null;
            if (offset == null) {
                assertThat(slice.path("nextOffset").isNull()).isTrue();
            }
            calls++;
        }
        assertThat(calls).isEqualTo(3);
        assertThat(joined.toString()).isEqualTo(body);

        JsonNode whole = callTool("get_note", Map.of("noteId", id.toString())).path("structuredContent");
        assertThat(whole.path("truncated").asBoolean()).isFalse();
        assertThat(whole.path("body").asString()).isEqualTo(body);
        assertThat(whole.path("version").asInt()).isZero();
    }

    @Test
    void rejectsInvalidInputWithValidationFailed() throws Exception {
        UUID id = note("검사", null, "본문");
        List<Map<String, Object>> cases = List.of(
                Map.of("tool", "search_notes", "args", Map.of("query", "가".repeat(101)), "field", "query"),
                Map.of("tool", "search_notes", "args", Map.of("limit", 51), "field", "limit"),
                Map.of("tool", "search_notes", "args", Map.of("topicId", "not-a-uuid"), "field", "topicId"),
                Map.of("tool", "search_notes", "args", Map.of("cursor", "bad"), "field", "cursor"),
                Map.of("tool", "search_notes", "args", Map.of("userId", UUID.randomUUID().toString()), "field", "userId"),
                Map.of("tool", "get_note", "args", Map.of(), "field", "noteId"),
                Map.of("tool", "get_note", "args", Map.of("noteId", "123"), "field", "noteId"),
                Map.of("tool", "get_note", "args", Map.of("noteId", id.toString(), "maxChars", 999), "field", "maxChars"),
                Map.of("tool", "get_note", "args", Map.of("noteId", id.toString(), "offset", 3), "field", "offset"),
                Map.of("tool", "get_note", "args", Map.of("noteId", id.toString(), "offset", -1), "field", "offset"));
        for (Map<String, Object> c : cases) {
            @SuppressWarnings("unchecked")
            JsonNode result = callTool((String) c.get("tool"), (Map<String, ?>) c.get("args"));
            assertThat(result.path("isError").asBoolean()).as(c.toString()).isTrue();
            JsonNode error = result.path("structuredContent").path("error");
            assertThat(error.path("code").asString()).as(c.toString()).isEqualTo("VALIDATION_FAILED");
            assertThat(error.path("retryable").asBoolean()).isTrue();
            assertThat(error.path("nextStep").asString()).isNotBlank();
            assertThat(error.path("details").path("fields").get(0).path("field").asString()).isEqualTo(c.get("field"));
            assertThat(mapper.readTree(result.path("content").get(0).path("text").asString()))
                    .isEqualTo(result.path("structuredContent"));
        }
    }

    @Test
    void otherUsersNotesAndTopicsLookExactlyLikeMissingIds(CapturedOutput output) throws Exception {
        UUID mine = note("내 노트", null, "본문");
        UUID theirNote = UUID.fromString(json(call("POST", "/api/notes",
                "{\"title\":\"타인 비밀 노트\",\"body\":\"타인 본문\"}", otherBearer)).path("id").asString());
        UUID theirTopic = UUID.fromString(json(call("POST", "/api/topics", "{\"name\":\"타인 주제\"}", otherBearer))
                .path("id").asString());

        JsonNode foreignNote = callTool("get_note", Map.of("noteId", theirNote.toString()));
        JsonNode missingNote = callTool("get_note", Map.of("noteId", UUID.randomUUID().toString()));
        assertThat(foreignNote.path("isError").asBoolean()).isTrue();
        assertThat(foreignNote.path("structuredContent").path("error").path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(foreignNote.path("structuredContent").path("error").path("retryable").asBoolean()).isFalse();
        assertThat(foreignNote.path("structuredContent")).isEqualTo(missingNote.path("structuredContent"));

        JsonNode foreignTopic = callTool("search_notes", Map.of("topicId", theirTopic.toString()));
        JsonNode missingTopic = callTool("search_notes", Map.of("topicId", UUID.randomUUID().toString()));
        assertThat(foreignTopic.path("structuredContent").path("error").path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(foreignTopic.path("structuredContent")).isEqualTo(missingTopic.path("structuredContent"));

        JsonNode all = callTool("search_notes", Map.of()).path("structuredContent");
        assertThat(all.path("total").asInt()).isEqualTo(1);
        assertThat(all.path("notes").get(0).path("id").asString()).isEqualTo(mine.toString());
        JsonNode topics = callTool("list_topics", Map.of()).path("structuredContent");
        assertThat(topics.path("topics")).isEmpty();

        callTool("search_notes", Map.of("query", "검색어흔적"));
        assertThat(output.getAll()).doesNotContain("검색어흔적").doesNotContain("타인 비밀 노트")
                .doesNotContain("타인 본문").doesNotContain(bearer);
    }
}
