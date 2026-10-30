package com.example.capstone.analysis.worker;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.example.capstone.analysis.client.AiCallException;
import com.example.capstone.analysis.service.AnalysisWorkService;
import com.example.capstone.analysis.service.AiActivationService;

@Component
public class AnalysisWorker {
    private final AnalysisWorkService work;
    private final AiActivationService activation;
    private final ObjectProvider<AnalysisPipeline> pipeline;
    private volatile long disabledRevision=-1;
    private final AtomicBoolean closing=new AtomicBoolean();
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ScheduledExecutorService heartbeats=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("analysis-heartbeat").factory());
    public AnalysisWorker(AnalysisWorkService work,AiActivationService activation,ObjectProvider<AnalysisPipeline> pipeline) {
        this.work=work;this.activation=activation;this.pipeline=pipeline;
    }
    @Scheduled(fixedDelayString="${capstone.ai.execution.poll-ms:1000}",initialDelayString="${capstone.ai.execution.poll-ms:1000}")
    public void tick() {
        if(!activation.configured()) { long revision=activation.stateRevision();if(disabledRevision!=revision) { work.cancelActive();disabledRevision=revision; } }
        else { disabledRevision=-1;if(activation.workerEnabled())runOne(); }
    }
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void startup() { if(!activation.configured()) { work.cancelActive();disabledRevision=activation.stateRevision(); } }
    public boolean runOne() {
        if(closing.get() || !activation.configured() || !busy.compareAndSet(false,true)) return false;
        try {
            if(!activation.available()) { work.expire();return false; }
            var claimed=work.claim();if(claimed==null) return false;
            AtomicBoolean lost=new AtomicBoolean();
            var heartbeat=heartbeats.scheduleAtFixedRate(()->{
                try { if(!work.heartbeat(claimed)) lost.set(true); } catch(RuntimeException failure) { lost.set(true); }
            },10,10,TimeUnit.SECONDS);
            try {
                if(!work.current(claimed,pipeline.getObject().policyFingerprint())) return true;
                var output=pipeline.getObject().run(claimed);
                if(closing.get()) return true;
                if(!lost.get() && activation.configured()) work.complete(claimed,output);
                else work.fail(claimed,"CANCELLED");
            } catch(AnalysisWorkService.LeaseLostException stale) {
                // 비활성 epoch만 취소한다. 다른 lease/input 소유자의 결과는 건드리지 않는다.
                work.retireDisabled(claimed);
            } catch(AiCallException failure) { work.fail(claimed,failure.reason().name()); }
            catch(RuntimeException failure) { work.fail(claimed,"INTERNAL"); }
            finally { heartbeat.cancel(false); }
            return true;
        } finally { busy.set(false); }
    }
    @PreDestroy void close() { closing.set(true);heartbeats.shutdownNow(); }
}
