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
    private final java.util.concurrent.atomic.AtomicBoolean previousState=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicLong stateRevision=new java.util.concurrent.atomic.AtomicLong();
    private final AiProperties properties;
    private final AiExecutionProperties execution;
    private final AiHttpClient client;
    private final ObjectProvider<AnalysisPipeline> pipeline;
    private final com.example.capstone.analysis.config.AiFeatureGate gate;
    public AiActivationService(AiProperties properties,AiExecutionProperties execution,AiHttpClient client,
            ObjectProvider<AnalysisPipeline> pipeline,com.example.capstone.analysis.config.AiFeatureGate gate) {
        this.properties=properties;this.execution=execution;this.client=client;this.pipeline=pipeline;
        this.gate=gate;
    }
    public boolean configured() {
        boolean configured=gate.allowed() && client.configured() && pipeline.getIfAvailable()!=null && pipeline.getObject().configured()
                && execution.budgetMicrounits()>0 && execution.currency()!=null && execution.currency().matches("[A-Z]{3}")
                && execution.requiredProfiles()!=null && !execution.requiredProfiles().isEmpty();
        if(!configured)gate.configurationUnavailable();
        if(previousState.getAndSet(configured)!=configured)stateRevision.incrementAndGet();
        return configured;
    }
    public long stateRevision() { return stateRevision.get(); }
    public boolean available() {
        if(!configured()) return false;
        try { client.ready(pipeline.getObject().requiredProfiles().isEmpty()?execution.requiredProfiles():pipeline.getObject().requiredProfiles());return configured(); }
        catch(AiCallException unavailable) { return false; }
    }
    public String policyFingerprint() { return pipeline.getObject().policyFingerprint(); }
    public boolean workerEnabled() { return configured() && execution.workerEnabled(); }
    public void requireAvailable() {
        if(!properties.enabled()) throw unavailable("NOT_DEPLOYED");
        if(!configured()) throw unavailable("MISCONFIGURED");
        try { client.ready(pipeline.getObject().requiredProfiles().isEmpty()?execution.requiredProfiles():pipeline.getObject().requiredProfiles());if(!configured())throw unavailable("MISCONFIGURED"); } catch(AiCallException failure) { throw unavailable(failure.reason().name()); }
    }
    private ApiException unavailable(String reason) { return new ApiException(ErrorCode.AI_UNAVAILABLE,"AI 분석은 아직 사용할 수 없습니다.",Map.of("reason",reason)); }
}
