package com.example.capstone.mcp.tool;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import com.example.capstone.mcp.config.McpProperties;

/**
 * 사용자별 MCP 도구 호출 제한(설계안 2.6). 단일 BE 인스턴스라 메모리 안의 이동 창으로 센다.
 * 재시작하면 카운터가 비워진다. 제한에 걸린 호출은 세지 않는다.
 */
@Component
@ConditionalOnBooleanProperty("capstone.mcp.enabled")
public class McpRateLimiter {

    enum Group { READ, WRITE, MOVE, RECOMMENDATION }

    private record Window(int limit, Duration length) {
    }

    private final Map<Group, Window> windows;
    private final Clock clock;
    private final Map<String, Deque<Instant>> calls = new ConcurrentHashMap<>();

    public McpRateLimiter(McpProperties properties, Clock clock) {
        McpProperties.RateLimits limits = properties.rateLimits();
        this.windows = Map.of(
                Group.READ, new Window(limits.readPerMinute(), Duration.ofMinutes(1)),
                Group.WRITE, new Window(limits.writePerMinute(), Duration.ofMinutes(1)),
                Group.MOVE, new Window(limits.movePerMinute(), Duration.ofMinutes(1)),
                Group.RECOMMENDATION, new Window(limits.recommendationPerTenMinutes(), Duration.ofMinutes(10)));
        this.clock = clock;
    }

    static Group group(String tool, boolean readOnly) {
        return switch (tool) {
            case "move_notes" -> Group.MOVE;
            case "request_recommendations" -> Group.RECOMMENDATION;
            default -> readOnly ? Group.READ : Group.WRITE;
        };
    }

    /** 호출을 허용하면 0, 막으면 다시 시도할 수 있을 때까지의 초(1 이상)를 돌려준다. */
    public long acquire(UUID user, String tool, boolean readOnly) {
        Group group = group(tool, readOnly);
        Window window = windows.get(group);
        Deque<Instant> times = calls.computeIfAbsent(user + ":" + group, ignored -> new ArrayDeque<>());
        synchronized (times) {
            Instant now = clock.instant();
            Instant start = now.minus(window.length());
            while (!times.isEmpty() && !times.peekFirst().isAfter(start)) {
                times.pollFirst();
            }
            if (times.size() >= window.limit()) {
                Duration wait = Duration.between(start, times.peekFirst());
                return Math.max(1, (wait.toMillis() + 999) / 1000);
            }
            times.addLast(now);
            return 0;
        }
    }
}
