package com.example.capstone.mcp.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;

/**
 * 도구 공통 처리: 사용자 식별, 결과 직렬화, 업무 오류를 isError 결과로 바꾸기.
 * 도구 인자와 결과 내용은 로그에 남기지 않는다.
 */
@Component
public class McpToolSupport {

    public static final String USER_ID = "capstone.userId";
    private static final Logger log = LoggerFactory.getLogger(McpToolSupport.class);

    private final ObjectMapper mapper;

    public McpToolSupport(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public SyncToolSpecification tool(McpSchema.Tool tool, Function<Call, Object> handler) {
        return SyncToolSpecification.builder().tool(tool).callHandler((context, request) -> {
            try {
                Call call = new Call(userId(context), request.arguments() == null ? Map.of() : request.arguments());
                return success(handler.apply(call));
            } catch (ApiException exception) {
                return error(exception.errorCode(), exception.getMessage(), exception.details());
            } catch (RuntimeException exception) {
                log.warn("MCP tool {} failed: {}", tool.name(), exception.getClass().getName());
                return error(ErrorCode.INTERNAL, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도하세요.", Map.of());
            }
        }).build();
    }

    public static McpSchema.ToolAnnotations hints(boolean readOnly, boolean destructive, boolean idempotent) {
        return McpSchema.ToolAnnotations.builder().readOnlyHint(readOnly).destructiveHint(destructive)
                .idempotentHint(idempotent).openWorldHint(false).build();
    }

    public Map<String, Object> schema(String json) {
        return mapper.readValue(json, Map.class);
    }

    private CallToolResult success(Object value) {
        JsonNode structured = mapper.valueToTree(value);
        return CallToolResult.builder().structuredContent(structured)
                .content(List.of(new McpSchema.TextContent(mapper.writeValueAsString(structured))))
                .isError(false).build();
    }

    private CallToolResult error(ErrorCode code, String message, Map<String, ?> details) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code.name());
        error.put("message", message);
        if (details != null && !details.isEmpty()) {
            error.put("details", details);
        }
        JsonNode structured = mapper.valueToTree(Map.of("error", error));
        return CallToolResult.builder().structuredContent(structured)
                .content(List.of(new McpSchema.TextContent(mapper.writeValueAsString(structured))))
                .isError(true).build();
    }

    private static UUID userId(McpTransportContext context) {
        Object value = context == null ? null : context.get(USER_ID);
        if (value == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다.");
        }
        return UUID.fromString(value.toString());
    }

    public record Call(UUID userId, Map<String, Object> arguments) {
    }
}
