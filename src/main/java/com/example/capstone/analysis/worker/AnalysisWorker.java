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
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ScheduledExecutorService heartbeats=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("analysis-heartbeat").factory());
    public AnalysisWorker(AnalysisWorkService work,AiActivationService activation,ObjectProvider<AnalysisPipeline> pipeline) {
        this.work=work;this.activation=activation;this.pipeline=pipeline;
    }
    @Scheduled(fixedDelayString="${capstone.ai.execution.poll-ms:1000}")
    public void tick() { if(activation.workerEnabled()) runOne(); }
    public boolean runOne() {
        if(!activation.configured() || !busy.compareAndSet(false,true)) return false;
        try {
            var claimed=work.claim();if(claimed==null) return false;
            AtomicBoolean lost=new AtomicBoolean();
            var heartbeat=heartbeats.scheduleAtFixedRate(()->{
                try { if(!work.heartbeat(claimed)) lost.set(true); } catch(RuntimeException failure) { lost.set(true); }
            },10,10,TimeUnit.SECONDS);
            try {
                if(!work.current(claimed)) return true;
                var output=pipeline.getObject().run(claimed);
                if(!lost.get() && activation.configured()) work.complete(claimed,output);
                else work.fail(claimed,"CANCELLED");
            } catch(AnalysisWorkService.LeaseLostException stale) {
                // A replacement owner/input fence owns the result. Never overwrite it.
            } catch(AiCallException failure) { work.fail(claimed,failure.reason().name()); }
            catch(RuntimeException failure) { work.fail(claimed,"INTERNAL"); }
            finally { heartbeat.cancel(false); }
            return true;
        } finally { busy.set(false); }
    }
    @PreDestroy void close() { heartbeats.shutdownNow(); }
}
