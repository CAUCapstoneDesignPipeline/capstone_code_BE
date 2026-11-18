package com.example.capstone.mcp.config;

import java.util.List;
import java.util.Map;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import tools.jackson.databind.json.JsonMapper;

import com.example.capstone.mcp.tool.McpToolSupport;
import com.example.capstone.mcp.tool.TopicTools;

@Configuration
@EnableConfigurationProperties(McpProperties.class)
@ConditionalOnBooleanProperty("capstone.mcp.enabled")
public class McpServerConfig {

    /**
     * Spring AI 기본 전송과 같지만, 요청 스레드에서 검증된 JWT의 sub를 꺼내 도구 호출 맥락에 싣는다.
     * 도구는 이 값만 사용자 식별에 쓴다.
     */
    @Bean
    WebMvcStatelessServerTransport webMvcStatelessServerTransport(
            @Qualifier("mcpServerJsonMapper") JsonMapper mcpServerJsonMapper,
            McpServerStreamableHttpProperties http) {
        return WebMvcStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(mcpServerJsonMapper))
                .messageEndpoint(http.getMcpEndpoint())
                .contextExtractor(request -> {
                    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                    if (authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()) {
                        return McpTransportContext.create(Map.of(McpToolSupport.USER_ID, jwt.getToken().getSubject()));
                    }
                    return McpTransportContext.EMPTY;
                })
                .build();
    }

    @Bean
    List<SyncToolSpecification> capstoneMcpTools(TopicTools topicTools) {
        return List.of(topicTools.listTopics());
    }
}
