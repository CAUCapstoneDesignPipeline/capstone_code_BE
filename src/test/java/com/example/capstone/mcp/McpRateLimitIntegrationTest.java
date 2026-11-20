package com.example.capstone.mcp;

import java.util.Map;

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

/** M7 사용자별 호출 제한을 실제 MCP 호출로 확인한다. 제한 수치는 시험에서 작게 줄였다. */
@SpringBootTest(properties = {"capstone.mcp.enabled=true", "capstone.mcp.write-enabled=true",
        "capstone.mcp.rate-limits.read-per-minute=4", "capstone.mcp.rate-limits.write-per-minute=3"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class McpRateLimitIntegrationTest extends McpIntegrationSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15");

    private static JsonNode error(JsonNode result) {
        return result.path("structuredContent").path("error");
    }

    @Test
    void limitsEachGroupPerUserWithRetryAfter() throws Exception {
        for (int i = 0; i < 4; i++) {
            assertThat(callTool("list_topics", Map.of()).path("isError").asBoolean()).isFalse();
        }
        JsonNode limited = callTool("list_topics", Map.of());
        assertThat(limited.path("isError").asBoolean()).isTrue();
        JsonNode error = error(limited);
        assertThat(error.path("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(error.path("retryable").asBoolean()).isTrue();
        assertThat(error.path("details").path("retryAfterSeconds").asLong()).isBetween(1L, 60L);
        assertThat(callTool(otherBearer, "list_topics", Map.of()).path("isError").asBoolean()).isFalse();

        for (int i = 0; i < 3; i++) {
            assertThat(callTool("create_topic", Map.of("name", "주제" + i)).path("isError").asBoolean()).isFalse();
        }
        assertThat(error(callTool("create_note", Map.of("title", "넘침"))).path("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(json(call("GET", "/api/notes", null)).path("notes")).isEmpty();
    }

}
