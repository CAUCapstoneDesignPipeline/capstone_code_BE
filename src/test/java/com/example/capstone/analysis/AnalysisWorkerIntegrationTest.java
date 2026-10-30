package com.example.capstone.analysis;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.example.capstone.analysis.support.ScriptedAiServer;
import com.example.capstone.analysis.service.AnalysisWorkService;
import com.example.capstone.analysis.worker.AnalysisWorker;
import com.example.capstone.analysis.worker.AnalysisPipeline;
import com.example.capstone.analysis.worker.AnalysisPipeline.Work;
import com.example.capstone.analysis.worker.AnalysisPipeline.PipelineOutput;
import com.example.capstone.support.ApiIntegrationSupport;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"capstone.ai.enabled=true","capstone.ai.execution.budget-microunits=1000","capstone.ai.execution.currency=KRW","capstone.ai.execution.required-profiles=fixture","capstone.ai.execution.worker-enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
@Import(AnalysisWorkerIntegrationTest.PipelineConfig.class)
class AnalysisWorkerIntegrationTest extends ApiIntegrationSupport {
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    static final ScriptedAiServer server=server();
    static ScriptedAiServer server() { try { return new ScriptedAiServer("synthetic-token-only",2097152); } catch(Exception e) { throw new IllegalStateException(); } }
    static final PipelineOutput EMPTY=new PipelineOutput(List.of(),List.of(),List.of());
    static final AtomicReference<Function<Work,PipelineOutput>> action=new AtomicReference<>(work->EMPTY);
    @TestConfiguration static class PipelineConfig { @Bean @Primary AnalysisPipeline pipeline() { return work->action.get().apply(work); } }
    @DynamicPropertySource static void ai(DynamicPropertyRegistry p) {
        p.add("capstone.ai.client.base-url",()->server.baseUri().toString());p.add("capstone.ai.client.service-token",()->"synthetic-token-only");
    }
    @Autowired AnalysisWorker worker;
    @Autowired AnalysisWorkService work;
    @BeforeEach void resetPipeline() { action.set(w->EMPTY); }
    private void ready() { server.enqueue(new ScriptedAiServer.Reply(200,"{\"status\":\"ready\",\"schemaVersion\":\"1.0\",\"profileVersions\":[\"fixture\"]}",java.util.Map.of())); }
    private UUID submit(UUID note) throws Exception { ready();return UUID.fromString(json(call("POST","/api/notes/"+note+"/analysis",null).andExpect(status().isAccepted())).path("id").asString()); }
    @Test void duplicateActiveRequestReusesJobAndWorkerPublishesOnlyOwnedSnapshots() throws Exception {
        UUID note=note("primary",null,"본문");UUID another=note("candidate",null,"근거");
        UUID job=submit(note);assertEquals(job,submit(note));
        assertEquals(2,jdbc.queryForObject("select count(*) from analysis_snapshot where job_id=?",Integer.class,job));
        assertTrue(worker.runOne());
        assertEquals("succeeded",json(call("GET","/api/notes/"+note+"/analysis",null)).path("job").path("lifecycleState").asString());
        assertEquals(0,jdbc.queryForObject("select count(*) from analysis_snapshot where job_id=? and user_id<>?",Integer.class,job,userId));
    }
    @Test void legacyPendingHistoryDoesNotPermanentlyBlockExplicitNewAnalysis() throws Exception {
        UUID note=note("legacy",null,"본문"),legacy=UUID.randomUUID();
        jdbc.update("insert into analysis_job(id,note_id,note_version,extractor,status) values(?,?,0,'legacy','pending')",legacy,note);
        UUID fresh=submit(note);assertNotEquals(legacy,fresh);
        assertEquals("failed",jdbc.queryForObject("select status from analysis_job where id=?",String.class,legacy));
        assertEquals("legacy",jdbc.queryForObject("select extractor from analysis_job where id=?",String.class,legacy));
        assertTrue(worker.runOne());assertEquals("succeeded",jdbc.queryForObject("select status from analysis_job where id=?",String.class,fresh));
    }
    @Test void concurrentAdmissionsAndClaimsDoNotDuplicateActiveWork() throws Exception {
        UUID note=note("concurrent",null,"본문");ready();ready();
        CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<String> request=()->{ start.await();return json(call("POST","/api/notes/"+note+"/analysis",null).andExpect(status().isAccepted())).path("id").asString(); };
            var a=executor.submit(request);var b=executor.submit(request);start.countDown();assertEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
            UUID secondNote=note("second",null,"근거");submit(secondNote);
            CountDownLatch claimStart=new CountDownLatch(1);
            java.util.concurrent.Callable<Work> claim=()->{ claimStart.await();return work.claim(); };
            var first=executor.submit(claim);var second=executor.submit(claim);claimStart.countDown();
            Work left=first.get(5,TimeUnit.SECONDS),right=second.get(5,TimeUnit.SECONDS);assertNotEquals(left.id(),right.id());
            work.fail(left,"TEST_END");work.fail(right,"TEST_END");
        }
    }
    @Test void leaseRecoveryFencesOldWorkerAndKeepsLogicalJob() throws Exception {
        UUID note=note("lease",null,"본문");UUID job=submit(note);
        Work old=work.claim();assertEquals(job,old.id());
        jdbc.update("update analysis_job set lease_until=clock_timestamp()-interval '1 second' where id=?",job);
        Work recovered=work.claim();assertEquals(job,recovered.id());assertNotEquals(old.leaseToken(),recovered.leaseToken());
        assertThrows(AnalysisWorkService.LeaseLostException.class,()->work.complete(old,EMPTY));
        work.complete(recovered,EMPTY);
        assertEquals("succeeded",jdbc.queryForObject("select status from analysis_job where id=?",String.class,job));
        assertEquals(2,jdbc.queryForObject("select attempt from analysis_job where id=?",Integer.class,job));
    }
    @Test void stageAttemptBudgetSurvivesLeaseRecoveryWithoutResetting() throws Exception {
        UUID note=note("retry budget",null,"본문");UUID job=submit(note);Work first=work.claim();
        var request=mapper.readTree("{\"context\":{\"stageExecutionId\":\""+UUID.randomUUID()+"\"}}");
        for(int attempt=0;attempt<3;attempt++) work.reserveAttempt(first,"facts","FACTS",request);
        jdbc.update("update analysis_job set lease_until=clock_timestamp()-interval '1 second' where id=?",job);
        Work replacement=work.claim();
        assertThrows(com.example.capstone.analysis.client.AiCallException.class,()->work.reserveAttempt(replacement,"facts","FACTS",request));
        assertEquals(3,jdbc.queryForObject("select attempts from analysis_stage where job_id=?",Integer.class,job));
        work.fail(replacement,"UNAVAILABLE");
    }
    @Test void editWhileExternalCallIsRunningSupersedesLateOutputAtomically() throws Exception {
        UUID note=note("edit",null,"before");UUID job=submit(note);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        action.set(w->{ entered.countDown();try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException(); } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException(); }return EMPTY; });
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var future=executor.submit(worker::runOne);assertTrue(entered.await(5,TimeUnit.SECONDS));
            call("PUT","/api/notes/"+note,"{\"title\":\"edit\",\"body\":\"after\",\"version\":0}").andExpect(status().isOk());
            release.countDown();assertTrue(future.get(5,TimeUnit.SECONDS));
        } finally { release.countDown(); }
        assertEquals("superseded",jdbc.queryForObject("select lifecycle_state from analysis_job where id=?",String.class,job));
        assertEquals(0,jdbc.queryForObject("select count(*) from analysis_result where job_id=?",Integer.class,job));
    }
}
