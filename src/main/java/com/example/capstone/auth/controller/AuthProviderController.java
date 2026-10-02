package com.example.capstone.auth.controller;

import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.capstone.auth.config.AuthEnvironment;
import com.example.capstone.auth.dto.response.AuthProviderListResponse;

@RestController
public class AuthProviderController {
    private final Environment environment;
    public AuthProviderController(Environment environment) { this.environment = environment; }
    @GetMapping("/api/auth/providers")
    public AuthProviderListResponse providers() {
        return new AuthProviderListResponse(List.of(), AuthEnvironment.isDevelopment(environment)
                && environment.getProperty("capstone.auth.dev-token.enabled", Boolean.class, false));
    }
}
