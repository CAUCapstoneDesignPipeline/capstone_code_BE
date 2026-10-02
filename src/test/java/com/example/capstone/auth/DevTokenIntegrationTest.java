package com.example.capstone.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "capstone.auth.dev-token.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class DevTokenIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15");
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;

    @Test
    void reusesIdentityAndConnectsOptionalCookieToMeAndRefresh() throws Exception {
        mvc.perform(get("/api/auth/providers")).andExpect(jsonPath("$.providers").isEmpty())
                .andExpect(jsonPath("$.devTokenEnabled").value(true));
        var first = mvc.perform(post("/api/auth/dev/token").contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie")).andReturn().getResponse();
        var json = mapper.readTree(first.getContentAsString());
        String id = json.path("user").path("id").asString();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + json.path("accessToken").asString()))
                .andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.providers[0]").value("dev"));
        var second = mvc.perform(post("/api/auth/dev/token").contentType("application/json")
                .content("{\"issueRefreshCookie\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(id))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")))
                .andReturn().getResponse();
        String raw = second.getHeader("Set-Cookie").split(";",2)[0].split("=",2)[1];
        mvc.perform(post("/api/auth/refresh").header("Origin", "http://localhost:5173")
                .cookie(new Cookie("CAPSTONE_REFRESH", raw)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(id));
        mvc.perform(post("/api/auth/dev/token").contentType("application/json")
                .content("{\"email\":\"second@example.com\",\"displayName\":\"두 번째\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(org.hamcrest.Matchers.not(id)));
        for (String bad : new String[]{"{\"email\":\"invalid\"}","{\"displayName\":\" \"}"}) {
            mvc.perform(post("/api/auth/dev/token").contentType("application/json").content(bad))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        assertThat(id).isNotBlank();
    }
}
