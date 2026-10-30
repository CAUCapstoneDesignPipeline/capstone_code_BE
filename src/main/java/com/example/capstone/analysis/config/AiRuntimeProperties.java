package com.example.capstone.analysis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("capstone.ai.runtime")
public record AiRuntimeProperties(@DefaultValue("false") boolean releaseAuthorized,String suspensionFile) { }
