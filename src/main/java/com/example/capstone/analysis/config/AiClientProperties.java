package com.example.capstone.analysis.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("capstone.ai.client")
public record AiClientProperties(String baseUrl, String serviceToken, String caCertificate,
        @DefaultValue("3s") Duration connectTimeout, @DefaultValue("130s") Duration callTimeout,
        @DefaultValue("40s") Duration embeddingTimeout, @DefaultValue("3") int maxAttempts,
        @DefaultValue("2") int maxConcurrent, @DefaultValue("2s") Duration retryBase) {
    public AiClientProperties {
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || callTimeout == null || callTimeout.isNegative() || callTimeout.isZero()
                || embeddingTimeout == null || embeddingTimeout.isNegative() || embeddingTimeout.isZero()
                || maxAttempts < 1 || maxAttempts > 3 || maxConcurrent < 1 || maxConcurrent > 2
                || retryBase == null || retryBase.isNegative() || retryBase.compareTo(Duration.ofSeconds(4)) > 0) {
            throw new IllegalStateException("Invalid bounded AI client configuration");
        }
    }
    @Override public String toString() { return "AiClientProperties[configuration=<redacted>]"; }
}
