package com.example.capstone.analysis.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("capstone.ai.execution")
public record AiExecutionProperties(@DefaultValue("true") boolean workerEnabled,
        @DefaultValue("0") long budgetMicrounits, String currency,
        @DefaultValue("extract-det-3,embeddings-bge-m3-1,merge-det-1,bridges-det-1,edges-det-1,explain-llm-8,explanation-det-3,links-det-1,facts-llm-1,derive-sound-1") Set<String> requiredProfiles) { }
