package com.example.capstone.mcp;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.capstone.auth.config.AuthenticationErrorHandler;
import com.example.capstone.auth.config.JwtConfig;
import com.example.capstone.auth.config.SecurityConfig;
import com.example.capstone.auth.controller.AuthController;
import com.example.capstone.auth.service.CurrentUserService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 설계안 2.8 P5: 운영 프로필에서는 서명이 맞아도 dev 클레임 토큰으로 /api/mcp에 들어올 수 없다. */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtConfig.class, AuthenticationErrorHandler.class})
@ActiveProfiles("prod")
@TestPropertySource(properties = "capstone.auth.jwt.secret=test-only-production-validation-key-32-bytes")
class McpProductionAuthWebMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtEncoder encoder;
    @MockitoBean
    private CurrentUserService users;

    @Test
    void rejectsDevClaimTokenOnMcpEndpointInProduction() throws Exception {
        Instant now = Instant.now().minusSeconds(1);
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("capstone-be").subject(UUID.randomUUID().toString())
                .issuedAt(now).expiresAt(now.plusSeconds(1800)).claim("dev", true).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        mvc.perform(post("/api/mcp").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType("application/json").header("Accept", "application/json, text/event-stream")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }
}
