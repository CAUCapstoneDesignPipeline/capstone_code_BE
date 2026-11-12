package com.example.capstone.mcp;

import java.util.Map;

import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

import com.example.capstone.support.ApiIntegrationSupport;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public abstract class McpIntegrationSupport extends ApiIntegrationSupport {

    protected ResultActions mcpRaw(String token, Object payload) throws Exception {
        var request = MockMvcRequestBuilders.post("/api/mcp")
                .contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .content(mapper.writeValueAsString(payload));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request);
    }

    protected JsonNode rpc(String token, String method, Map<String, ?> params) throws Exception {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", 1);
        payload.put("method", method);
        payload.put("params", params);
        return json(mcpRaw(token, payload).andExpect(status().isOk()));
    }

    protected JsonNode callTool(String token, String name, Map<String, ?> arguments) throws Exception {
        return rpc(token, "tools/call", Map.of("name", name, "arguments", arguments)).path("result");
    }

    protected JsonNode callTool(String name, Map<String, ?> arguments) throws Exception {
        return callTool(bearer, name, arguments);
    }
}
