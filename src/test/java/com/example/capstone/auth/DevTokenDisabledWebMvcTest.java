package com.example.capstone.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import com.example.capstone.auth.config.*;
import com.example.capstone.auth.controller.AuthProviderController;
import com.example.capstone.auth.controller.DevTokenController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({AuthProviderController.class, DevTokenController.class})
@Import({SecurityConfig.class, JwtConfig.class, AuthenticationErrorHandler.class})
@ActiveProfiles("test")
@TestPropertySource(properties = "capstone.auth.dev-token.enabled=false")
class DevTokenDisabledWebMvcTest {
    @Autowired private MockMvc mvc;
    @Test
    void disabledLoginIs404AndNotAdvertised() throws Exception {
        mvc.perform(post("/api/auth/dev/token").header("Authorization", "Bearer invalid")
                .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/api/auth/providers")).andExpect(jsonPath("$.devTokenEnabled").value(false));
    }
}
