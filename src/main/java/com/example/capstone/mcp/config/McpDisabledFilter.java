package com.example.capstone.mcp.config;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;

import com.example.capstone.global.exception.ErrorCode;
import com.example.capstone.global.response.ErrorResponse;

/**
 * MCP가 꺼져 있으면 토큰 유무와 관계없이 /api/mcp를 404로 닫는다.
 * 보안 필터보다 먼저 돌며, 다른 경로와 기존 인증 규칙은 건드리지 않는다.
 */
@Configuration
@ConditionalOnBooleanProperty(name = "capstone.mcp.enabled", havingValue = false, matchIfMissing = true)
public class McpDisabledFilter {

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> mcpDisabledFilterRegistration(ObjectMapper mapper) {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                mapper.writeValue(response.getOutputStream(),
                        ErrorResponse.of(ErrorCode.NOT_FOUND, "요청한 대상을 찾을 수 없습니다.", Map.of()));
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/mcp", "/api/mcp/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("mcpDisabledFilter");
        return registration;
    }
}
