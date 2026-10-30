package com.example.capstone.analysis.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("capstone.ai.pipeline")
public record AiPipelineProperties(String policyFile,String attestationFile,String attestationSha256,
        @DefaultValue("false") boolean syntheticStub,Map<String,String> profiles) { }
