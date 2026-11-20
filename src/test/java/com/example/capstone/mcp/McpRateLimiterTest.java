package com.example.capstone.mcp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.example.capstone.mcp.config.McpProperties;
import com.example.capstone.mcp.tool.McpRateLimiter;

import static org.assertj.core.api.Assertions.assertThat;

/** 이동 창 호출 제한. 시계를 직접 움직여 창이 지나면 다시 허용되는지 본다. */
class McpRateLimiterTest {

    static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-11-20T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static McpRateLimiter limiter(MovableClock clock) {
        var limits = new McpProperties.RateLimits(2, 1, 1, 1);
        var properties = new McpProperties(true, true,
                new McpProperties.Limits(10000, 1000, 30000, 20, 50, 100, 50), limits);
        return new McpRateLimiter(properties, clock);
    }

    @Test
    void readWindowSlidesAndReportsSecondsUntilTheOldestCallLeaves() {
        MovableClock clock = new MovableClock();
        McpRateLimiter limiter = limiter(clock);
        UUID user = UUID.randomUUID();
        assertThat(limiter.acquire(user, "get_note", true)).isZero();
        clock.advance(Duration.ofSeconds(20));
        assertThat(limiter.acquire(user, "search_notes", true)).isZero();
        assertThat(limiter.acquire(user, "list_topics", true)).isEqualTo(40);
        clock.advance(Duration.ofSeconds(40));
        assertThat(limiter.acquire(user, "list_topics", true)).isZero();
        assertThat(limiter.acquire(user, "list_topics", true)).isEqualTo(20);
    }

    @Test
    void groupsAndUsersAreCountedSeparately() {
        MovableClock clock = new MovableClock();
        McpRateLimiter limiter = limiter(clock);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertThat(limiter.acquire(a, "create_note", false)).isZero();
        assertThat(limiter.acquire(a, "rename_note", false)).isEqualTo(60);
        assertThat(limiter.acquire(a, "move_notes", false)).isZero();
        assertThat(limiter.acquire(a, "move_notes", false)).isEqualTo(60);
        assertThat(limiter.acquire(a, "request_recommendations", false)).isZero();
        assertThat(limiter.acquire(a, "request_recommendations", false)).isEqualTo(600);
        assertThat(limiter.acquire(a, "get_recommendations", true)).isZero();
        assertThat(limiter.acquire(b, "create_note", false)).isZero();
        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.acquire(a, "update_note", false)).isZero();
        assertThat(limiter.acquire(a, "request_recommendations", false)).isEqualTo(540);
    }
}
