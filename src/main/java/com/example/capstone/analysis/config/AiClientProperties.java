package com.example.capstone.analysis.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("capstone.ai.client")
public record AiClientProperties(String baseUrl, String serviceToken, String caCertificate,
        @DefaultValue("3s") Duration connectTimeout, @DefaultValue("130s") Duration callTimeout,
        @DefaultValue("40s") Duration embeddingTimeout, @DefaultValue("3") int maxAttempts,
        @DefaultValue("2") int maxConcurrent, @DefaultValue("2s") Duration retryBase) {
    public boolean valid() {
        return !(connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero() || connectTimeout.compareTo(Duration.ofSeconds(3))>0
                || callTimeout == null || callTimeout.isNegative() || callTimeout.isZero() || callTimeout.compareTo(Duration.ofSeconds(130))>0
                || embeddingTimeout == null || embeddingTimeout.isNegative() || embeddingTimeout.isZero() || embeddingTimeout.compareTo(Duration.ofSeconds(40))>0
                || maxAttempts < 1 || maxAttempts > 3 || maxConcurrent < 1 || maxConcurrent > 2
                || retryBase == null || retryBase.isNegative() || retryBase.compareTo(Duration.ofSeconds(4)) > 0);
    }
    @Override public String toString() { return "AiClientProperties[configuration=<redacted>]"; }
}
