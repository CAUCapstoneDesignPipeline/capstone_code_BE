package com.example.capstone.analysis.service;

import java.util.Arrays;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import com.example.capstone.analysis.client.AiHttpClient;
import com.example.capstone.analysis.client.AiCallException;
import com.example.capstone.analysis.config.AiProperties;
import com.example.capstone.analysis.config.AiExecutionProperties;
import com.example.capstone.analysis.worker.AnalysisPipeline;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;

@Service
public class AiActivationService {
    private final AiProperties properties;
    private final AiExecutionProperties execution;
    private final AiHttpClient client;
    private final ObjectProvider<AnalysisPipeline> pipeline;
    private final boolean local;
    public AiActivationService(AiProperties properties,AiExecutionProperties execution,AiHttpClient client,
            ObjectProvider<AnalysisPipeline> pipeline,Environment environment) {
        this.properties=properties;this.execution=execution;this.client=client;this.pipeline=pipeline;
        this.local=Arrays.stream(environment.getActiveProfiles()).anyMatch(p -> p.equals("test") || p.equals("local"));
    }
    public boolean configured() {
        return properties.enabled() && local && client.configured() && pipeline.getIfAvailable()!=null
                && execution.budgetMicrounits()>0 && execution.currency()!=null && execution.currency().matches("[A-Z]{3}")
                && execution.requiredProfiles()!=null && !execution.requiredProfiles().isEmpty();
    }
    public String policyFingerprint() { return pipeline.getObject().policyFingerprint(); }
    public boolean workerEnabled() { return configured() && execution.workerEnabled(); }
    public void requireAvailable() {
        if(!properties.enabled()) throw unavailable("NOT_DEPLOYED");
        if(!configured()) throw unavailable("MISCONFIGURED");
        try { client.ready(execution.requiredProfiles()); } catch(AiCallException failure) { throw unavailable(failure.reason().name()); }
    }
    private ApiException unavailable(String reason) { return new ApiException(ErrorCode.AI_UNAVAILABLE,"AI 분석은 아직 사용할 수 없습니다.",Map.of("reason",reason)); }
}
