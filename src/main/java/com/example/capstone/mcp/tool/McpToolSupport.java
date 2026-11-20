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

    private static final java.util.regex.Pattern UUID_TEXT =
            java.util.regex.Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final ObjectMapper mapper;
    private final McpRateLimiter limiter;

    public McpToolSupport(ObjectMapper mapper, McpRateLimiter limiter) {
        this.mapper = mapper;
        this.limiter = limiter;
    }

    public SyncToolSpecification tool(McpSchema.Tool tool, Function<Call, Object> handler) {
        Set<String> allowed = properties(tool.inputSchema());
        boolean readOnly = tool.annotations() != null && Boolean.TRUE.equals(tool.annotations().readOnlyHint());
        return SyncToolSpecification.builder().tool(tool).callHandler((context, request) -> {
            long started = System.nanoTime();
            UUID user = null;
            Map<String, Object> raw = request.arguments() == null ? Map.of() : request.arguments();
            Outcome outcome;
            try {
                user = userId(context);
                long retryAfter = limiter.acquire(user, tool.name(), readOnly);
                if (retryAfter > 0) {
                    outcome = new Outcome(result(Map.of("error", McpErrors.rateLimited(retryAfter)), true), "RATE_LIMITED");
                } else {
                    Object value = handler.apply(new Call(user, new McpArguments(raw, allowed)));
                    outcome = value instanceof Failure failure
                            ? new Outcome(result(failure.content(), true), "ALL_FAILED")
                            : new Outcome(result(value, false), "OK");
                }
            } catch (ApiException exception) {
                outcome = new Outcome(error(exception.errorCode(), exception.getMessage(), exception.details()),
                        exception.errorCode().name());
            } catch (RuntimeException exception) {
                log.warn("MCP tool {} failed: {}", tool.name(), exception.getClass().getName());
                outcome = new Outcome(error(ErrorCode.INTERNAL, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도하세요.", Map.of()),
                        ErrorCode.INTERNAL.name());
            }
            record(tool.name(), readOnly, user, raw, outcome, started);
            return outcome.result();
        }).build();
    }

    /**
     * 설계안 2.6 기록: requestId, 사용자 id, 도구 이름, 결과 코드, 걸린 시간, 응답 크기.
     * 쓰기 도구는 인자 중 UUID 형식의 대상 id와 건수만 더한다. 인자 값(제목·본문·검색어)과 토큰은 남기지 않는다.
     */
    private void record(String tool, boolean readOnly, UUID user, Map<String, Object> raw, Outcome outcome, long started) {
        long millis = (System.nanoTime() - started) / 1_000_000;
        int bytes = outcome.result().content().stream()
                .mapToInt(content -> content instanceof McpSchema.TextContent text
                        ? text.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length : 0)
                .sum();
        String requestId = UUID.randomUUID().toString();
        if (readOnly) {
            log.info("mcp tool call requestId={} user={} tool={} result={} durationMs={} responseBytes={}",
                    requestId, user, tool, outcome.code(), millis, bytes);
            return;
        }
        List<String> targets = targets(raw);
        log.info("mcp tool call requestId={} user={} tool={} result={} durationMs={} responseBytes={} targets={} targetCount={}",
                requestId, user, tool, outcome.code(), millis, bytes, targets, targets.size());
    }

    private static List<String> targets(Map<String, Object> raw) {
        List<String> ids = new java.util.ArrayList<>();
        raw.forEach((name, value) -> {
            if (!name.endsWith("Id") && !name.endsWith("Ids")) {
                return;
            }
            for (Object item : value instanceof List<?> list ? list : java.util.Collections.singletonList(value)) {
                if (item instanceof String text && UUID_TEXT.matcher(text).matches()) {
                    ids.add(text);
                }
            }
        });
        return ids;
    }

    private record Outcome(CallToolResult result, String code) {
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
