package com.example.capstone.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.example.capstone.analysis.support.SyntheticPipelineServer;
import com.example.capstone.analysis.service.AnalysisWorkService;
import com.example.capstone.analysis.worker.AnalysisWorker;
import com.example.capstone.analysis.worker.AnalysisPipeline.PipelineOutput;
import com.example.capstone.support.ApiIntegrationSupport;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"capstone.ai.enabled=true","capstone.ai.pipeline.synthetic-stub=true","capstone.ai.execution.budget-microunits=1000","capstone.ai.execution.currency=KRW","capstone.ai.execution.worker-enabled=false","capstone.ai.execution.poll-ms=3600000"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
class AiActivationIntegrationTest extends ApiIntegrationSupport {
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    static final SyntheticPipelineServer server=new SyntheticPipelineServer();
    static final Path root=directory(),policy=root.resolve("policy.json"),stop=root.resolve("suspend");
    static Path directory() { try { var p=Files.createTempDirectory("ai-activation-");Files.writeString(p.resolve("policy.json"),SyntheticPipelineServer.POLICY);return p; } catch(Exception e) { throw new IllegalStateException(e); } }
    @DynamicPropertySource static void ai(DynamicPropertyRegistry p) { p.add("capstone.ai.client.base-url",()->server.uri().toString());p.add("capstone.ai.client.service-token",()->SyntheticPipelineServer.TOKEN);p.add("capstone.ai.pipeline.policy-file",policy::toString);p.add("capstone.ai.runtime.suspension-file",stop::toString); }
    @AfterAll static void cleanup() throws Exception { server.close();Files.deleteIfExists(stop);Files.delete(policy);Files.delete(root); }
    @Autowired AnalysisWorkService work;
    @Autowired AnalysisWorker worker;
    @BeforeEach void reset() throws Exception { Files.deleteIfExists(stop);server.mode="normal";server.readiness="ready";server.requests.clear();server.release.countDown(); }
    UUID submit(UUID note) throws Exception { return UUID.fromString(json(call("POST","/api/notes/"+note+"/analysis",null).andExpect(status().isAccepted())).path("id").asString()); }
    boolean capability() throws Exception { return json(call("GET","/api/capabilities",null).andExpect(status().isOk())).path("analysisEnabled").asBoolean(); }
    @Test void readinessMismatchOutageAndTimeoutAreBoundedAndNeverCreateJobs() throws Exception {
        UUID note=note("원문",null,"A>B");var captures=mapper.createArrayNode();
        capture(captures,"ready","GET","/capabilities",call("GET","/api/capabilities",null),200);assertTrue(capability());
        for(String mode:List.of("missing","unavailable","timeout")) {
            server.readiness=mode;long start=System.nanoTime();assertFalse(capability());assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(4));
            capture(captures,mode,"GET","/capabilities",call("GET","/api/capabilities",null),200);
            capture(captures,mode+"-post","POST","/notes/{noteId}/analysis",call("POST","/api/notes/"+note+"/analysis",null),503);
            assertEquals(0,jdbc.queryForObject("select count(*) from analysis_job where user_id=?",Integer.class,userId));
        }
        server.readiness="ready";assertTrue(capability());writeCaptures("readiness",captures);
    }
    private void capture(tools.jackson.databind.node.ArrayNode captures,String id,String method,String template,org.springframework.test.web.servlet.ResultActions response,int status) throws Exception {
        assertEquals(status,response.andReturn().getResponse().getStatus());captures.addObject().put("caseId",id).put("origin","mockmvc").put("method",method).put("pathTemplate",template).put("status",status).set("body",json(response));
    }
    private void writeCaptures(String name,tools.jackson.databind.node.ArrayNode cases) throws Exception {
        var envelope=mapper.createObjectNode().put("schemaVersion",1).put("syntheticOnly",true);envelope.putObject("producer").put("commit","working-tree").put("suite",getClass().getSimpleName());envelope.set("cases",cases);
        Path path=Path.of(".local/verification/results/public-capability-"+name+".json");Files.createDirectories(path.getParent());Files.writeString(path,envelope.toPrettyString());
    }
    @Test void suspensionFencesQueuedRunningAndLateResultsButKeepsNotesAndHistory() throws Exception {
        UUID note=note("앞",null,"A>B");note("뒤",null,"B>C");UUID successful=submit(note);assertTrue(worker.runOne());
        UUID candidate=jdbc.queryForObject("select id from analysis_result where job_id=?",UUID.class,successful);
        String accept="{\"expectedRevision\":1,\"action\":\"accept\",\"reason\":null,\"learning\":null,\"editedConclusion\":null,\"idempotencyKey\":\"before-disable\"}";
        call("POST","/api/candidates/"+candidate+"/review",accept).andExpect(status().isCreated());
        UUID running=submit(note);var claimed=work.claim();UUID other=note("새 노트",null,"원문");UUID queued=submit(other);
        Files.writeString(stop,"disabled");assertFalse(capability());worker.startup();
        var captures=mapper.createArrayNode();capture(captures,"suspended","GET","/capabilities",call("GET","/api/capabilities",null),200);
        capture(captures,"suspended-post","POST","/notes/{noteId}/analysis",call("POST","/api/notes/"+note+"/analysis",null),503);writeCaptures("suspension",captures);
        for(UUID job:List.of(running,queued)) assertEquals("cancelled",jdbc.queryForObject("select lifecycle_state from analysis_job where id=?",String.class,job));
        assertThrows(AnalysisWorkService.LeaseLostException.class,()->work.complete(claimed,new PipelineOutput(List.of(),List.of(),List.of())));
        call("POST","/api/notes/"+note+"/analysis",null).andExpect(status().isServiceUnavailable());call("GET","/api/notes/"+note,null).andExpect(status().isOk());
        assertEquals(1,json(call("GET","/api/reviews",null)).path("reviews").size());call("GET","/api/candidates/"+candidate,null).andExpect(status().isOk());
        Files.delete(stop);assertTrue(capability());assertNotEquals(running,submit(note));worker.runOne();
    }
    @Test void stopDuringHttpCannotPublishResponseThatArrivesAfterCancellation() throws Exception {
        UUID note=note("앞",null,"A>B");note("뒤",null,"B>C");UUID job=submit(note);server.mode="blocked";server.entered=new CountDownLatch(1);server.release=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var future=executor.submit(worker::runOne);assertTrue(server.entered.await(5,TimeUnit.SECONDS));Files.writeString(stop,"disabled");worker.tick();server.release.countDown();assertTrue(future.get(5,TimeUnit.SECONDS));
        } finally { server.release.countDown();Files.deleteIfExists(stop); }
        assertEquals("cancelled",jdbc.queryForObject("select lifecycle_state from analysis_job where id=?",String.class,job));
        assertEquals(0,jdbc.queryForObject("select count(*) from analysis_result where job_id=?",Integer.class,job));assertEquals(0,jdbc.queryForObject("select count(*) from analysis_graph_item where job_id=?",Integer.class,job));
    }
    @Test @DirtiesContext void responseProfileMismatchQuarantinesCapabilitiesUntilRestart() throws Exception {
        UUID note=note("앞",null,"A>B");note("뒤",null,"B>C");UUID job=submit(note);server.mode="profile-mismatch";assertTrue(worker.runOne());
        assertFalse(capability());assertEquals(0,jdbc.queryForObject("select count(*) from analysis_result where job_id=?",Integer.class,job));
        server.mode="normal";assertFalse(capability());call("GET","/api/notes/"+note,null).andExpect(status().isOk());
    }
}
