package com.example.capstone.mcp;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** M6 추천 도구, AI가 꺼진 환경. 설계안 2.8 I10과 P1·P4의 추천 도구 부분. */
@SpringBootTest(properties = {"capstone.mcp.enabled=true", "capstone.mcp.write-enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class McpRecommendationToolsAiOffIntegrationTest extends McpIntegrationSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15");

    private long jobs() {
        return jdbc.queryForObject("select count(*) from analysis_job", Long.class);
    }

    @Test
    void requestWithAiOffFailsWithoutCreatingAJob() throws Exception {
        UUID note = note("멱등성", null, "같은 요청을 여러 번 보내도 결과가 같다.");
        JsonNode result = callTool("request_recommendations", Map.of("noteId", note.toString()));
        assertThat(result.path("isError").asBoolean()).isTrue();
        JsonNode error = result.path("structuredContent").path("error");
        assertThat(error.path("code").asString()).isEqualTo("AI_UNAVAILABLE");
        assertThat(error.path("retryable").asBoolean()).isFalse();
        assertThat(error.path("nextStep").asString()).contains("다시 시도하지 않는다");
        assertThat(jobs()).isZero();
    }

    @Test
    void getByNoteWithoutAnalysisReturnsNullJob() throws Exception {
        UUID note = note("분석 전", null, "본문");
        JsonNode result = callTool("get_recommendations", Map.of("noteId", note.toString()));
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").has("job")).isTrue();
        assertThat(result.path("structuredContent").path("job").isNull()).isTrue();
    }

    @Test
    void otherUsersNotesAndJobsLookMissing() throws Exception {
        UUID foreign = UUID.fromString(json(call("POST", "/api/notes", "{\"title\":\"남의 노트\",\"body\":\"비밀\"}", otherBearer))
                .path("id").asString());
        for (String tool : new String[] {"request_recommendations", "get_recommendations"}) {
            JsonNode result = callTool(tool, Map.of("noteId", foreign.toString()));
            assertThat(result.path("isError").asBoolean()).isTrue();
            assertThat(result.path("structuredContent").path("error").path("code").asString()).isEqualTo("NOT_FOUND");
            assertThat(result.toString()).doesNotContain("남의 노트");
        }
        JsonNode missing = callTool("get_recommendations", Map.of("jobId", UUID.randomUUID().toString()));
        assertThat(missing.path("structuredContent").path("error").path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(jobs()).isZero();
    }

    @Test
    void getRequiresExactlyOneOfJobIdAndNoteId() throws Exception {
        UUID id = UUID.randomUUID();
        JsonNode both = callTool("get_recommendations", Map.of("jobId", id.toString(), "noteId", id.toString()));
        JsonNode neither = callTool("get_recommendations", Map.of());
        JsonNode bad = callTool("get_recommendations", Map.of("jobId", "not-a-uuid"));
        for (JsonNode result : new JsonNode[] {both, neither, bad}) {
            assertThat(result.path("isError").asBoolean()).isTrue();
            assertThat(result.path("structuredContent").path("error").path("code").asString()).isEqualTo("VALIDATION_FAILED");
        }
        assertThat(neither.path("structuredContent").path("error").path("details").path("fields").get(0).path("reason").asString())
                .isEqualTo("required");
    }

    @Test
    void toolHintsMatchDesign() throws Exception {
        JsonNode request = null, get = null;
        for (JsonNode tool : rpc(bearer, "tools/list", Map.of()).path("result").path("tools")) {
            if (tool.path("name").asString().equals("request_recommendations")) request = tool;
            if (tool.path("name").asString().equals("get_recommendations")) get = tool;
        }
        assertThat(request).isNotNull();
        assertThat(get).isNotNull();
        assertThat(request.path("annotations").path("readOnlyHint").asBoolean()).isFalse();
        assertThat(request.path("annotations").path("destructiveHint").asBoolean()).isFalse();
        assertThat(request.path("annotations").path("idempotentHint").asBoolean()).isFalse();
        assertThat(request.path("description").asString()).contains("사용자가 요청했을 때만");
        assertThat(get.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
        assertThat(get.path("annotations").path("idempotentHint").asBoolean()).isTrue();
        assertThat(get.path("description").asString()).contains("quote는 바꾸거나 요약하지 말고");
    }
}
