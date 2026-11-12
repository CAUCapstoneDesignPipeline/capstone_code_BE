package com.example.capstone.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * MCP 서버 스위치, 쓰기 도구 스위치와 응답 크기 제한. 스위치 기본값은 꺼짐이며 운영 설정에서는 켜지 않는다.
 * 크기 제한 기본값은 설계안 2.6의 [가정] 수치다.
 */
@ConfigurationProperties("capstone.mcp")
public record McpProperties(boolean enabled, boolean writeEnabled, @DefaultValue Limits limits) {

    public record Limits(
            @DefaultValue("10000") int noteDefaultChars,
            @DefaultValue("1000") int noteMinChars,
            @DefaultValue("30000") int noteMaxChars,
            @DefaultValue("20") int searchDefaultLimit,
            @DefaultValue("50") int searchMaxLimit,
            @DefaultValue("100") int searchQueryMaxChars) {
    }
}
