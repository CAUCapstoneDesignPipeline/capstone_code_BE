package com.example.capstone.analysis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("capstone.ai")
public record AiProperties(boolean enabled) { }
