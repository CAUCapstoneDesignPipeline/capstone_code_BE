package com.example.capstone.analysis.config;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.NoSuchFileException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 외부 호출과 저장에서 공통 사용하며 pipeline/service 의존 순환을 만들지 않는다. */
@Component
public class AiFeatureGate {
    private final AiProperties flags;
    private final AiRuntimeProperties runtime;
    private final boolean development;
    private final boolean syntheticForbidden;
    private com.example.capstone.analysis.repository.AiRuntimeFenceRepository fence;
    private Boolean previousAllowed;
    private final java.util.concurrent.atomic.AtomicBoolean pendingRevocation=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean invalidConfigurationObserved=new java.util.concurrent.atomic.AtomicBoolean();
    @org.springframework.beans.factory.annotation.Autowired
    public AiFeatureGate(AiProperties flags,AiRuntimeProperties runtime,AiPipelineProperties pipeline,Environment environment,
            com.example.capstone.analysis.repository.AiRuntimeFenceRepository fence) {
        this(flags,runtime,pipeline,environment);this.fence=fence;
    }
    public AiFeatureGate(AiProperties flags,AiRuntimeProperties runtime,AiPipelineProperties pipeline,Environment environment) {
        this.flags=flags;this.runtime=runtime;development=environment.matchesProfiles("local","test") && !environment.matchesProfiles("prod");
        syntheticForbidden=pipeline.syntheticStub() && !development;
    }
    public synchronized boolean allowed() {
        flushRevocation();
        boolean result=readAllowed();
        if(!result && !Boolean.FALSE.equals(previousAllowed) && fence!=null)fence.disable();
        previousAllowed=result;
        return result;
    }
    public void configurationUnavailable() {
        if(invalidConfigurationObserved.compareAndSet(false,true) && fence!=null) {
            try { fence.disable(); } catch(RuntimeException failure) { invalidConfigurationObserved.set(false);throw failure; }
        }
    }
    /** 이미 DB fence 공유 잠금을 가진 트랜잭션의 마지막 비차단 검사. */
    public boolean stillAllowed() {
        boolean allowed=readAllowed();
        if(!allowed) {
            pendingRevocation.set(true);
            if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCompletion(int status) { flushRevocation(); }
                });
            } else flushRevocation();
        }
        return allowed;
    }
    // 공유 잠금을 해제한 뒤, API 실패 응답 또는 다음 작업 접수 이전에 영속화한다.
    public synchronized void flushRevocation() {
        if(pendingRevocation.getAndSet(false) && fence!=null) {
            try { fence.disable(); } catch(RuntimeException failure) { pendingRevocation.set(true);throw failure; }
        }
    }
    private boolean readAllowed() {
        if(!flags.enabled() || syntheticForbidden || (!development && !runtime.releaseAuthorized())) return false;
        if(runtime.suspensionFile()==null || runtime.suspensionFile().isBlank()) return true;
        try { Files.readAttributes(Path.of(runtime.suspensionFile()),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return false; }
        catch(NoSuchFileException absent) { return true; }
        catch(Exception unreadable) { return false; }
    }
}
