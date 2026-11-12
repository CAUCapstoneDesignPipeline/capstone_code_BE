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

/** 쓰기 스위치가 꺼져 있으면 쓰기 도구가 목록에 없고 호출도 거부된다(설계안 2.8 I11). */
@SpringBootTest(properties = {"capstone.mcp.enabled=true", "capstone.mcp.write-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class McpWriteSwitchOffIntegrationTest extends McpIntegrationSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15");

    @Test
    void writeToolsAreHiddenAndRejected() throws Exception {
        JsonNode tools = rpc(bearer, "tools/list", Map.of()).path("result").path("tools");
        assertThat(tools.toString()).contains("list_topics", "search_notes", "get_note")
                .doesNotContain("create_note", "update_note", "rename_note", "create_topic", "move_notes");

        JsonNode response = rpc(bearer, "tools/call", Map.of("name", "create_note", "arguments", Map.of("title", "몰래 저장")));
        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(response.has("result")).isFalse();
        assertThat(json(call("GET", "/api/notes", null)).path("notes")).isEmpty();
    }
}
