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
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.ai.util.JacksonUtils;

import com.example.capstone.mcp.tool.McpToolSupport;
import com.example.capstone.mcp.tool.NoteReadTools;
import com.example.capstone.mcp.tool.NoteWriteTools;
import com.example.capstone.mcp.tool.OrganizeTools;
import com.example.capstone.mcp.tool.TopicTools;
import org.springframework.ai.mcp.customizer.McpStatelessSyncServerCustomizer;

@Configuration
@EnableConfigurationProperties(McpProperties.class)
@ConditionalOnBooleanProperty("capstone.mcp.enabled")
public class McpServerConfig {

    /**
     * Spring AI 기본 mcpServerJsonMapper와 같은 설정이되, Map 안의 null 값은 남긴다.
     * 기본 매퍼는 JSON-RPC 요청을 변환하면서 도구 인자의 null을 지워 move_notes의 "topicId": null(미분류)이
     * "topicId 없음"으로 바뀐다. 프로토콜 객체의 null 필드는 지금처럼 빠진다.
     */
    @Bean(name = "mcpServerJsonMapper", defaultCandidate = false)
    JsonMapper mcpServerJsonMapper() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .addModules(JacksonUtils.instantiateAvailableModules())
                .changeDefaultPropertyInclusion(incl -> JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
                .build();
    }

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
            NoteReadTools noteReadTools, NoteWriteTools noteWriteTools, OrganizeTools organizeTools) {
        List<SyncToolSpecification> tools = new java.util.ArrayList<>(
                List.of(topicTools.listTopics(), noteReadTools.searchNotes(), noteReadTools.getNote()));
        if (properties.writeEnabled()) {
            tools.addAll(List.of(noteWriteTools.createNote(), noteWriteTools.updateNote(), noteWriteTools.renameNote(),
                    organizeTools.createTopic(), organizeTools.moveNotes()));
        }
        return List.copyOf(tools);
    }
}
