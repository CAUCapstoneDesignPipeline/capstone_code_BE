package com.example.capstone.mcp;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;
import com.example.capstone.mcp.tool.McpToolSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2 호환 스파이크: Boot 4.1.1·Jackson 3·기존 JWT 필터 위에서 STATELESS MCP 서버가 동작하는지 확인한다.
 */
@SpringBootTest(properties = "capstone.mcp.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class McpServerSpikeIntegrationTest extends McpIntegrationSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15");

    @Autowired ApplicationContext context;

    @TestConfiguration
    static class SpikeProbeTools {
        @Bean
        List<SyncToolSpecification> spikeProbeTools(McpToolSupport support) {
            var input = support.schema("{\"type\":\"object\",\"properties\":{}}");
            var whoami = McpSchema.Tool.builder().name("spike_whoami").description("spike").inputSchema(input).build();
            var fail = McpSchema.Tool.builder().name("spike_fail").description("spike").inputSchema(input).build();
            return List.of(
                    support.tool(whoami, call -> {
                        var auth = SecurityContextHolder.getContext().getAuthentication();
                        return Map.of("fromTransportContext", call.userId().toString(),
                                "fromSecurityContextHolder", auth instanceof JwtAuthenticationToken jwt
                                        ? jwt.getToken().getSubject() : "none",
                                "thread", Thread.currentThread().getName());
                    }),
                    support.tool(fail, call -> {
                        throw new ApiException(ErrorCode.NOT_FOUND, "삭제되었거나 찾을 수 없는 노트입니다.");
                    }));
        }
    }

    @Test
    void rejectsRequestsWithoutTokenBeforeAnyTool() throws Exception {
        mcpRaw(null, Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                .andExpect(status().isUnauthorized());
        mcpRaw("not-a-token", Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void negotiatesProtocolVersion() throws Exception {
        for (String requested : List.of("2025-11-25", "2025-06-18", "2026-07-28")) {
            JsonNode result = rpc(bearer, "initialize", Map.of("protocolVersion", requested,
                    "capabilities", Map.of(), "clientInfo", Map.of("name", "spike", "version", "0"))).path("result");
            String expected = requested.equals("2026-07-28") ? "2025-11-25" : requested;
            assertThat(result.path("protocolVersion").asString()).isEqualTo(expected);
            assertThat(result.path("serverInfo").path("name").asString()).isEqualTo("capstone-notes");
        }
    }

    @Test
    void listsAndCallsListTopicsOnlyForTokenOwner() throws Exception {
        topic("운영체제");
        topic("네트워크");
        call("POST", "/api/topics", "{\"name\":\"타인 주제\"}", otherBearer).andExpect(status().isCreated());

        JsonNode tools = rpc(bearer, "tools/list", Map.of()).path("result").path("tools");
        JsonNode listTopics = null;
        for (JsonNode tool : tools) {
            if (tool.path("name").asString().equals("list_topics")) listTopics = tool;
        }
        assertThat(listTopics).isNotNull();
        assertThat(listTopics.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
        assertThat(listTopics.path("annotations").path("destructiveHint").asBoolean()).isFalse();
        assertThat(listTopics.path("annotations").path("idempotentHint").asBoolean()).isTrue();
        assertThat(listTopics.path("annotations").path("openWorldHint").asBoolean()).isFalse();
        assertThat(listTopics.path("outputSchema").path("required").toString()).contains("unassignedNoteCount");

        JsonNode result = callTool("list_topics", Map.of());
        assertThat(result.path("isError").asBoolean()).isFalse();
        JsonNode structured = result.path("structuredContent");
        assertThat(structured.path("topics")).hasSize(2);
        assertThat(structured.path("topics").toString()).doesNotContain("타인 주제");
        assertThat(structured.path("topics").get(0).path("createdAt").asString()).endsWith("Z");
        assertThat(mapper.readTree(result.path("content").get(0).path("text").asString())).isEqualTo(structured);

        JsonNode other = callTool(otherBearer, "list_topics", Map.of()).path("structuredContent");
        assertThat(other.path("topics")).hasSize(1);
        assertThat(other.path("topics").get(0).path("name").asString()).isEqualTo("타인 주제");
    }

    @Test
    void toolReadsAuthenticatedUserInStatelessMode() throws Exception {
        JsonNode result = callTool("spike_whoami", Map.of()).path("structuredContent");
        assertThat(result.path("fromTransportContext").asString()).isEqualTo(userId.toString());
        assertThat(result.path("fromSecurityContextHolder").asString()).isEqualTo(userId.toString());
        JsonNode other = callTool(otherBearer, "spike_whoami", Map.of()).path("structuredContent");
        assertThat(other.path("fromTransportContext").asString()).isEqualTo(otherId.toString());
    }

    @Test
    void businessErrorBecomesIsErrorResultWithStructuredContent() throws Exception {
        JsonNode result = callTool("spike_fail", Map.of());
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("structuredContent").path("error").path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(result.path("structuredContent").path("error").path("message").asString())
                .isEqualTo("삭제되었거나 찾을 수 없는 노트입니다.");
        assertThat(mapper.readTree(result.path("content").get(0).path("text").asString()))
                .isEqualTo(result.path("structuredContent"));

        JsonNode unknown = rpc(bearer, "tools/call", Map.of("name", "no_such_tool", "arguments", Map.of()));
        assertThat(unknown.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void applicationKeepsBootJackson3MapperAlongsideMcpMapper(
            @Autowired @Qualifier("mcpServerJsonMapper") JsonMapper mcpMapper) {
        ObjectMapper primary = context.getBean(ObjectMapper.class);
        assertThat(primary).isSameAs(mapper).isNotSameAs(mcpMapper);
        assertThat(primary.getClass().getPackageName()).startsWith("tools.jackson");
    }
}
