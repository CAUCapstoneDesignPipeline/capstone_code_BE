package com.example.capstone.mcp.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
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
 * 도구 공통 처리: 사용자 식별, 입력 검사, 결과 직렬화, 업무 오류를 isError 결과로 바꾸기.
 * 도구 인자와 결과 내용은 로그에 남기지 않는다.
 */
@Component
@ConditionalOnBooleanProperty("capstone.mcp.enabled")
public class McpToolSupport {

    public static final String USER_ID = "capstone.userId";
    private static final Logger log = LoggerFactory.getLogger(McpToolSupport.class);

    private final ObjectMapper mapper;

    public McpToolSupport(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public SyncToolSpecification tool(McpSchema.Tool tool, Function<Call, Object> handler) {
        Set<String> allowed = properties(tool.inputSchema());
        return SyncToolSpecification.builder().tool(tool).callHandler((context, request) -> {
            try {
                Map<String, Object> raw = request.arguments() == null ? Map.of() : request.arguments();
                Call call = new Call(userId(context), new McpArguments(raw, allowed));
                Object value = handler.apply(call);
                if (value instanceof Failure failure) {
                    return result(failure.content(), true);
                }
                return result(value, false);
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

    @SuppressWarnings("unchecked")
    public Map<String, Object> schema(String json) {
        return mapper.readValue(json, Map.class);
    }

    private CallToolResult result(Object value, boolean isError) {
        JsonNode structured = mapper.valueToTree(value);
        return CallToolResult.builder().structuredContent(structured)
                .content(List.of(new McpSchema.TextContent(mapper.writeValueAsString(structured))))
                .isError(isError).build();
    }

    private CallToolResult error(ErrorCode code, String message, Map<String, ?> details) {
        return result(Map.of("error", McpErrors.error(code, message, details)), true);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> properties(Map<String, Object> inputSchema) {
        Object properties = inputSchema == null ? null : inputSchema.get("properties");
        return properties instanceof Map<?, ?> map ? ((Map<String, Object>) map).keySet() : Set.of();
    }

    private static UUID userId(McpTransportContext context) {
        Object value = context == null ? null : context.get(USER_ID);
        if (value == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "로그인이 필요합니다.");
        }
        return UUID.fromString(value.toString());
    }

    public record Call(UUID userId, McpArguments arguments) {
    }

    /** 도구가 업무 결과 모양 그대로 isError: true로 돌려줘야 할 때 쓴다(예: move_notes 전부 실패). */
    public record Failure(Object content) {
    }
}
