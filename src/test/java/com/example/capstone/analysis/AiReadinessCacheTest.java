package com.example.capstone.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;
import com.example.capstone.analysis.client.AiHttpClient;
import com.example.capstone.analysis.client.AiResponseValidator;
import com.example.capstone.analysis.client.AiSchemaValidator;
import com.example.capstone.analysis.config.AiClientProperties;
import com.example.capstone.analysis.config.AiExecutionProperties;
import com.example.capstone.analysis.config.AiFeatureGate;
import com.example.capstone.analysis.config.AiPipelineProperties;
import com.example.capstone.analysis.config.AiProperties;
import com.example.capstone.analysis.config.AiRuntimeProperties;
import com.example.capstone.analysis.service.AiActivationService;
import com.example.capstone.analysis.support.ScriptedAiServer;
import com.example.capstone.analysis.worker.AnalysisPipeline;
import com.example.capstone.global.exception.ApiException;
import static org.junit.jupiter.api.Assertions.*;

/** capabilities·worker tick의 readiness 호출을 짧은 캐시로 줄이되 gate(suspension)는 매번 본다. */
class AiReadinessCacheTest {
    private static final String TOKEN="synthetic-service-token-only";
    private static final String READY="{\"status\":\"ready\",\"schemaVersion\":\"1.0\",\"profileVersions\":[\"fixture\"]}";
    @TempDir Path temporary;
    private final JsonMapper mapper=JsonMapper.builder().build();
    private final AiSchemaValidator schemas=new AiSchemaValidator(mapper);

    private AiActivationService service(ScriptedAiServer server,Duration cache) {
        var env=new MockEnvironment();env.setActiveProfiles("test");
        var client=new AiHttpClient(new AiClientProperties(server.baseUri().toString(),TOKEN,null,Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(2),1,1,Duration.ZERO),
                mapper,schemas,new AiResponseValidator(schemas),env);
        var beans=new StaticListableBeanFactory();beans.addBean("pipeline",(AnalysisPipeline)work->null);
        var gate=new AiFeatureGate(new AiProperties(true),new AiRuntimeProperties(false,temporary.resolve("suspend").toString()),new AiPipelineProperties(null,null,null,false,Map.of()),env);
        return new AiActivationService(new AiProperties(true),new AiExecutionProperties(true,1000,"KRW",Set.of("fixture"),cache),client,beans.getBeanProvider(AnalysisPipeline.class),gate);
    }
    private static void assertNoMoreRequests(ScriptedAiServer server) {
        assertThrows(IllegalStateException.class,()->server.takeRequest(Duration.ofMillis(100)));
    }

    @Test void readyResultIsReusedWithinTtlWhileSuspensionAppliesImmediately() throws Exception {
        try(var server=new ScriptedAiServer(TOKEN,AiHttpClient.REQUEST_LIMIT)) {
            server.enqueue(new ScriptedAiServer.Reply(200,READY,Map.of()));var activation=service(server,Duration.ofSeconds(10));
            for(int i=0;i<5;i++) assertTrue(activation.available());
            activation.requireAvailable();
            server.takeRequest(Duration.ofSeconds(1));assertNoMoreRequests(server);
            Files.writeString(temporary.resolve("suspend"),"on");assertFalse(activation.available());
            Files.delete(temporary.resolve("suspend"));assertTrue(activation.available());assertNoMoreRequests(server);
        }
    }
    @Test void failureReasonIsCachedForTheSameWindow() throws Exception {
        try(var server=new ScriptedAiServer(TOKEN,AiHttpClient.REQUEST_LIMIT)) {
            server.enqueue(new ScriptedAiServer.Reply(503,"{}",Map.of()));var activation=service(server,Duration.ofSeconds(10));
            assertFalse(activation.available());assertFalse(activation.available());
            var refused=assertThrows(ApiException.class,activation::requireAvailable);
            assertEquals("UNAVAILABLE",refused.details().get("reason"));
            server.takeRequest(Duration.ofSeconds(1));assertNoMoreRequests(server);
        }
    }
    @Test void zeroTtlAsksEveryTime() throws Exception {
        try(var server=new ScriptedAiServer(TOKEN,AiHttpClient.REQUEST_LIMIT)) {
            server.enqueue(new ScriptedAiServer.Reply(200,READY,Map.of()));server.enqueue(new ScriptedAiServer.Reply(200,READY,Map.of()));
            var activation=service(server,Duration.ZERO);assertTrue(activation.available());assertTrue(activation.available());
            server.takeRequest(Duration.ofSeconds(1));server.takeRequest(Duration.ofSeconds(1));
        }
    }
    @Test void concurrentCallersShareOneReadinessRequest() throws Exception {
        try(var server=new ScriptedAiServer(TOKEN,AiHttpClient.REQUEST_LIMIT);var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var reply=ScriptedAiServer.Reply.gated(200,READY);server.enqueue(reply);var activation=service(server,Duration.ofSeconds(10));
            var results=new ArrayList<Future<Boolean>>();for(int i=0;i<4;i++) results.add(executor.submit(activation::available));
            server.takeRequest(Duration.ofSeconds(2));reply.release();
            for(var result:results) assertTrue(result.get(5,TimeUnit.SECONDS));
            assertNoMoreRequests(server);
        }
    }
}
