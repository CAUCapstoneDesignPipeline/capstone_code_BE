package com.example.capstone.analysis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("capstone.ai")
public record AiProperties(boolean enabled) {
    public AiProperties {
        if (enabled) {
            throw new IllegalStateException("AI activation is not supported by this AI-off build");
        }
    }
}
