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

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스위치 기본값(꺼짐)에서는 토큰과 관계없이 /api/mcp가 404이고 다른 경로의 인증 규칙은 그대로다. */
@SpringBootTest(properties = "capstone.mcp.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class McpDisabledIntegrationTest extends McpIntegrationSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15");

    @Test
    void mcpEndpointIsNotFoundWhenSwitchIsOff() throws Exception {
        Map<String, Object> list = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list");
        mcpRaw(bearer, list).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mcpRaw(null, list).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mcp"))
                .andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/topics"))
                .andExpect(status().isUnauthorized());
        call("GET", "/api/topics", null).andExpect(status().isOk());
    }
}
