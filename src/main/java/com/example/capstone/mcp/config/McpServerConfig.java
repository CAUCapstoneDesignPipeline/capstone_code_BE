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
import com.example.capstone.mcp.tool.NoteReadTools;
import com.example.capstone.mcp.tool.NoteWriteTools;
import com.example.capstone.mcp.tool.TopicTools;
import org.springframework.ai.mcp.customizer.McpStatelessSyncServerCustomizer;

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

    /**
     * SDK 기본 입력 검사는 오류 모양이 설계안 2.6과 다르고 실패한 인자 값을 경고 로그에 남긴다.
     * 끄고 McpArguments가 VALIDATION_FAILED로 검사한다.
     */
    @Bean
    McpStatelessSyncServerCustomizer capstoneMcpInputValidation() {
        return server -> server.validateToolInputs(false);
    }

    /** 도구 목록 순서는 설계안 2.3 요약표 순서를 따른다. */
    @Bean
    List<SyncToolSpecification> capstoneMcpTools(McpProperties properties, TopicTools topicTools,
            NoteReadTools noteReadTools, NoteWriteTools noteWriteTools) {
        List<SyncToolSpecification> tools = new java.util.ArrayList<>(
                List.of(topicTools.listTopics(), noteReadTools.searchNotes(), noteReadTools.getNote()));
        if (properties.writeEnabled()) {
            tools.addAll(List.of(noteWriteTools.createNote(), noteWriteTools.updateNote(), noteWriteTools.renameNote()));
        }
        return List.copyOf(tools);
    }
}
