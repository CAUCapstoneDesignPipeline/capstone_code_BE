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
    private final Object readinessLock=new Object();
    private volatile Readiness readiness;
    /** 마지막 readiness 결과. failure가 null이면 준비됨. expiresAt은 System.nanoTime 기준이다. */
    private record Readiness(java.util.Set<String> profiles,long expiresAt,AiCallException.Reason failure) { }
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
    public void flushRevocation() { gate.flushRevocation(); }
    public long stateRevision() { return stateRevision.get(); }
    public boolean available() {
        if(!configured()) return false;
        try { ready();return configured(); }
        catch(AiCallException unavailable) { return false; }
    }
    /**
     * capabilities 요청과 worker의 대기 tick이 AI readiness를 매번 부르지 않게 결과(성공·실패 이유)를 잠깐 다시 쓴다.
     * 동시에 만료되면 한 요청만 AI에 묻는다. gate·설정 검사는 캐시하지 않으므로 suspension·quarantine은 바로 반영된다.
     */
    private void ready() {
        var required=pipeline.getObject().requiredProfiles().isEmpty()?execution.requiredProfiles():pipeline.getObject().requiredProfiles();
        long ttl=execution.readinessCacheTtl().toNanos();
        if(ttl<=0) { client.ready(required);return; }
        var cached=readiness;
        if(!fresh(cached,required)) synchronized(readinessLock) {
            cached=readiness;
            if(!fresh(cached,required)) {
                AiCallException.Reason failure=null;
                try { client.ready(required); } catch(AiCallException unavailable) { failure=unavailable.reason(); }
                cached=new Readiness(java.util.Set.copyOf(required),System.nanoTime()+ttl,failure);readiness=cached;
            }
        }
        if(cached.failure()!=null) throw new AiCallException(cached.failure());
    }
    private static boolean fresh(Readiness cached,java.util.Set<String> required) {
        return cached!=null && cached.profiles().equals(required) && System.nanoTime()-cached.expiresAt()<0;
    }
    public String policyFingerprint() { return pipeline.getObject().policyFingerprint(); }
    public boolean workerEnabled() { return configured() && execution.workerEnabled(); }
    public void requireAvailable() {
        if(!properties.enabled()) throw unavailable("NOT_DEPLOYED");
        if(!configured()) throw unavailable("MISCONFIGURED");
        try { ready();if(!configured())throw unavailable("MISCONFIGURED"); } catch(AiCallException failure) { throw unavailable(failure.reason().name()); }
    }
    private ApiException unavailable(String reason) { return new ApiException(ErrorCode.AI_UNAVAILABLE,"AI 분석은 아직 사용할 수 없습니다.",Map.of("reason",reason)); }
}
