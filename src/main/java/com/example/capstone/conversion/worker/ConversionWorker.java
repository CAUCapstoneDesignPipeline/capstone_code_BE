package com.example.capstone.conversion.worker;

import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import com.example.capstone.conversion.config.ConversionProperties;
import com.example.capstone.conversion.service.*;

@Component
public class ConversionWorker {
    private final ConversionService service;private final ConversionProperties config;private final SourceStorage storage;private final ConversionWire wire;private final ObjectProvider<DocumentConverter> converters;
    private final AtomicBoolean busy=new AtomicBoolean(),closing=new AtomicBoolean();
    // interrupt를 무시하는 adapter도 추가 thread/queue를 누적할 수 없다. 엔진 자체 종료를 보장하지는 않는다.
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<>(),Thread.ofPlatform().daemon(true).name("conversion-call").factory(),new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("conversion-heartbeat").factory());
    public ConversionWorker(ConversionService service,ConversionProperties config,SourceStorage storage,ConversionWire wire,ObjectProvider<DocumentConverter> converters) { this.service=service;this.config=config;this.storage=storage;this.wire=wire;this.converters=converters; }
    @Scheduled(fixedDelayString="${capstone.conversion.poll-ms:1000}",initialDelayString="${capstone.conversion.poll-ms:1000}") public void tick() { if(config.workerEnabled())runOne(); }
    public boolean runOne() {
        if(closing.get() || !service.configured() || !busy.compareAndSet(false,true))return false;
        try {
            UUID lease=UUID.randomUUID();var job=service.claim(lease);if(job==null)return false;UUID id=UUID.fromString(job.path("id").asString()),user=UUID.fromString(job.path("user_id").asString());Instant deadline=Instant.parse(job.path("deadline").asString());
            AtomicBoolean lost=new AtomicBoolean();var beat=heartbeat.scheduleAtFixedRate(()->{try{if(!service.heartbeat(id,lease))lost.set(true);}catch(RuntimeException invalid){lost.set(true);}},10,10,TimeUnit.SECONDS);
            JsonNode request=null;String failure="CONVERSION_FAILED";
            try {
                for(int n=job.path("attempt").asInt();n<2 && !lost.get() && !closing.get();n++) {
                    if(!deadline.isAfter(Instant.now())) { failure="TIMEOUT";break; }
                    request=service.attempt(user,id,lease,UUID.randomUUID());UUID object=UUID.fromString(request.path("source").path("objectRef").asString());
                    byte[] sourceBytes=storage.read(object,request.path("source").path("contentSha256").asString(),request.path("source").path("byteSize").asLong());
                    JsonNode input=request;Future<byte[]> future=null;
                    try {
                        DocumentConverter converter=converters.getObject();future=executor.submit(()->{try(var source=new java.io.ByteArrayInputStream(sourceBytes)){return converter.convert(input,source);}});
                        long timeout=Math.max(1,Math.min(config.callSeconds()*1000L,Duration.between(Instant.now(),deadline).toMillis()));
                        JsonNode response=wire.validate(future.get(timeout,TimeUnit.MILLISECONDS),request,job.path("engine_revision").asString());
                        if(!closing.get() && !lost.get())service.finish(user,id,lease,request,response,null);return true;
                    } catch(TimeoutException timeout) { failure="TIMEOUT"; }
                    catch(ExecutionException exception) { if(exception.getCause() instanceof DocumentConverter.Retryable)failure="UNAVAILABLE";else {failure="CONVERSION_FAILED";break;} }
                    catch(RejectedExecutionException unavailable) { failure="UNAVAILABLE";break; }
                    catch(ConversionWire.InvalidOutput invalid) { failure="INVALID_OUTPUT";break; }
                    finally { if(future!=null && !future.isDone())future.cancel(true); }
                }
                if(!closing.get())service.finish(user,id,lease,request,null,lost.get()?"CANCELLED":failure);
            } catch(ConversionService.Lost lostLease) { /* 다른 소유자의 상태를 덮어쓰지 않는다. */ }
            catch(Exception failureException) { if(failureException instanceof InterruptedException)Thread.currentThread().interrupt();if(!closing.get())service.finish(user,id,lease,request,null,"CONVERSION_FAILED"); }
            finally { beat.cancel(false); }
            return true;
        } finally { busy.set(false); }
    }
    @PreDestroy public void close() { closing.set(true);executor.shutdownNow();heartbeat.shutdownNow(); }
}
