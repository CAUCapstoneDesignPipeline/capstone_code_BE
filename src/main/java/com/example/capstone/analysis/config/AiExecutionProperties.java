package com.example.capstone.analysis.config;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("capstone.ai.execution")
public record AiExecutionProperties(@DefaultValue("true") boolean workerEnabled,
        @DefaultValue("0") long budgetMicrounits, String currency,
        @DefaultValue("extract-det-3,embeddings-bge-m3-1,merge-det-1,bridges-det-1,edges-det-1,explain-llm-8,explanation-det-3,links-det-1,facts-llm-3,derive-sound-1") Set<String> requiredProfiles,
        @DefaultValue("10s") Duration readinessCache) {
    /** readiness 결과를 다시 쓰는 시간. 0이면 매번 묻는다. 음수는 0, 60초 초과는 60초로 본다. */
    public Duration readinessCacheTtl() {
        if(readinessCache==null || readinessCache.isNegative()) return Duration.ZERO;
        return readinessCache.compareTo(Duration.ofSeconds(60))>0?Duration.ofSeconds(60):readinessCache;
    }
}
