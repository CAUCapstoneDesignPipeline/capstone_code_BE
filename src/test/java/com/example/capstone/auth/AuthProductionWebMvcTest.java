package com.example.capstone.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.capstone.auth.config.AuthenticationErrorHandler;
import com.example.capstone.auth.config.JwtConfig;
import com.example.capstone.auth.config.SecurityConfig;
import com.example.capstone.auth.controller.AuthController;
import com.example.capstone.auth.dto.response.MeResponse;
import com.example.capstone.auth.service.CurrentUserService;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtConfig.class, AuthenticationErrorHandler.class})
@ActiveProfiles("prod")
@TestPropertySource(properties = "capstone.auth.jwt.secret=test-only-production-validation-key-32-bytes")
class AuthProductionWebMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtEncoder encoder;
    @MockitoBean
    private CurrentUserService users;

    @Test
    void rejectsEvenCorrectlySignedTokensWithAnyDevClaimInProduction() throws Exception {
        Instant now = Instant.now().minusSeconds(1);
        for (Object claim : List.of(true, false, "true")) {
            JwtClaimsSet claims = JwtClaimsSet.builder().issuer("capstone-be")
                    .subject(UUID.randomUUID().toString()).issuedAt(now).expiresAt(now.plusSeconds(1800))
                    .claim("dev", claim).build();
            mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + sign(claims)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        }
        verifyNoInteractions(users);
    }

    @Test
    void acceptsOrdinarySignedTokenInProduction() throws Exception {
        UUID id = UUID.randomUUID();
        when(users.getMe(id)).thenReturn(new MeResponse(id, "user@example.com", "사용자", List.of("google")));
        Instant now = Instant.now().minusSeconds(1);
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("capstone-be").subject(id.toString())
                .issuedAt(now).expiresAt(now.plusSeconds(1800)).build();
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + sign(claims)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
        mvc.perform(get("/swagger-ui.html")).andExpect(status().isUnauthorized());
    }

    private String sign(JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
