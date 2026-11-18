package com.example.capstone.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP 서버 스위치. 기본값은 꺼짐이며 운영 설정에서는 켜지 않는다.
 */
@ConfigurationProperties("capstone.mcp")
public record McpProperties(boolean enabled) {
}
