package com.example.capstone.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.example.capstone.analysis.support.SyntheticPipelineServer;
import com.example.capstone.analysis.service.AnalysisWorkService;
import com.example.capstone.analysis.worker.GraphAnalysisPipeline;
import com.example.capstone.analysis.worker.AnalysisWorker;
import com.example.capstone.support.ApiIntegrationSupport;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"capstone.ai.enabled=true","capstone.ai.pipeline.synthetic-stub=true","capstone.ai.execution.budget-microunits=1000","capstone.ai.execution.currency=KRW","capstone.ai.execution.worker-enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
class DerivationPipelineIntegrationTest extends ApiIntegrationSupport {
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    static final SyntheticPipelineServer server=new SyntheticPipelineServer();
    static final Path policy=policy();
    static Path policy() { try { var path=Files.createTempFile("facts-policy-",".json");Files.writeString(path,SyntheticPipelineServer.POLICY);return path; } catch(Exception e) { throw new IllegalStateException(e); } }
    @DynamicPropertySource static void ai(DynamicPropertyRegistry p) { p.add("capstone.ai.client.base-url",()->server.uri().toString());p.add("capstone.ai.client.service-token",()->SyntheticPipelineServer.TOKEN);p.add("capstone.ai.pipeline.policy-file",policy::toString); }
    @AfterAll static void cleanup() throws Exception { server.close();Files.delete(policy); }
    @Autowired AnalysisWorkService work;
    @Autowired GraphAnalysisPipeline pipeline;
    @Autowired AnalysisWorker worker;
    @BeforeEach void reset() { server.mode="normal";server.calculation=true;server.calculationState="sound";server.requests.clear(); }
    UUID submit(UUID note) throws Exception { return UUID.fromString(json(call("POST","/api/notes/"+note+"/analysis",null).andExpect(status().isAccepted())).path("id").asString()); }
    UUID execute(UUID note) throws Exception { UUID id=submit(note);var job=work.claim();assertEquals(id,job.id());work.complete(job,pipeline.run(job));assertEquals("succeeded",jdbc.queryForObject("select status from analysis_job where id=?",String.class,id));return id; }
    String body(int i) { return server.derivationFixture.path("request").path("documents").get(i).path("body").asString(); }
    long factsCalls() { return server.requests.stream().filter(r->r.has("knownSubjects")).count(); }
    @Test void factsCacheKeepsOriginalProvenanceAndRemapsRowsForEachJob() throws Exception {
        UUID primary=note("알고리즘",null,body(0));note("구현",topic("다른 주제"),body(1));UUID first=execute(primary);assertEquals(2,factsCalls());
        assertEquals(11,jdbc.queryForObject("select count(*) from analysis_fact where job_id=?",Integer.class,first));assertEquals(1,jdbc.queryForObject("select count(*) from analysis_dependency where job_id=?",Integer.class,first));
        assertEquals(2,json(call("GET","/api/candidates",null)).path("results").size());
        UUID second=execute(primary);assertEquals(2,factsCalls());
        assertEquals(11,jdbc.queryForObject("select count(*) from analysis_fact where job_id=? and provenance->>'cacheHit'='true'",Integer.class,second));
        assertEquals(11,jdbc.queryForObject("select count(*) from analysis_fact where job_id=? and provenance->'sourceContext'->>'analysisJobId'=?",Integer.class,second,first.toString()));
        assertEquals(0,jdbc.queryForObject("select count(*) from analysis_result_evidence e join analysis_fact f on f.id=e.fact_id where e.job_id=? and f.job_id<>e.job_id",Integer.class,second));
        assertEquals(2,jdbc.queryForObject("select count(distinct identity_sha256) from analysis_result where user_id=?",Integer.class,userId));
        assertEquals(2,json(call("GET","/api/candidates",null)).path("results").size());
    }
    @Test void copiedTextNeverCollidesOnAiFactIdAndIncludesKnownSubjects() throws Exception {
        UUID primary=note("알고리즘",null,body(0));note("구현1",null,body(1));note("구현2",null,body(1));UUID job=execute(primary);
        assertEquals(16,jdbc.queryForObject("select count(distinct external_ref) from analysis_fact where job_id=?",Integer.class,job));
        assertEquals(16,jdbc.queryForObject("select count(*) from analysis_fact where job_id=?",Integer.class,job));
        assertTrue(server.requests.stream().filter(r->r.has("knownSubjects")).anyMatch(r->!r.path("knownSubjects").isEmpty()));
        assertTrue(server.requests.stream().filter(r->r.has("facts")).allMatch(r->r.path("facts").size()==16));
    }
    @Test void versionAndKnownSubjectsChangeMissCacheWithoutReusingGeneratedConclusions() throws Exception {
        UUID primary=note("알고리즘",null,body(0));note("구현",null,body(1));execute(primary);assertEquals(2,factsCalls());
        server.mode="known-change";call("PUT","/api/notes/"+primary,mapper.writeValueAsString(java.util.Map.of("title","알고리즘","body",body(0)+"추가 원문","version",0))).andExpect(status().isOk());
        execute(primary);assertEquals(4,factsCalls());
        assertEquals(0,jdbc.queryForObject("select count(*) from analysis_fact where user_id=? and value->>'origin' not in ('extracted','manual')",Integer.class,userId));
        assertTrue(server.requests.stream().filter(r->r.has("knownSubjects")).anyMatch(r->r.path("knownSubjects").toString().contains("changed_subject")));
    }
    @Test void missingKnownRejectedAndUncheckedStatesPreserveHistoryWithoutFalseRecommendation() throws Exception {
        UUID primary=note("알고리즘",null,body(0));note("구현",null,body(1));
        for(String state:java.util.List.of("missing","known","rejected","unchecked")) {
            server.calculationState=state;UUID job=execute(primary);assertEquals(1,jdbc.queryForObject("select count(*) from analysis_dependency where job_id=?",Integer.class,job));
            assertEquals(state.equals("missing")?1:0,json(call("GET","/api/candidates",null)).path("results").size());
        }
        assertEquals(8,jdbc.queryForObject("select count(*) from analysis_result where user_id=?",Integer.class,userId));
    }
    @Test void boundedSelectionRetainsStoredStatedResultAcrossTopics() throws Exception {
        UUID stated=note("원래 결론",topic("결론 주제"),"기존 결론: 다익스트라의 시간 상한");execute(stated);
        assertEquals(1,jdbc.queryForObject("select count(*) from analysis_fact where user_id=? and kind='stated_result'",Integer.class,userId));
        for(int i=0;i<22;i++)note("최근 비관련 "+i,null,"별도 원문");
        UUID primary=note("알고리즘",null,body(0));UUID job=execute(primary);
        assertEquals(20,jdbc.queryForObject("select count(*) from analysis_snapshot where job_id=?",Integer.class,job));
        assertEquals(1,jdbc.queryForObject("select count(*) from analysis_snapshot where job_id=? and original_note_id=?",Integer.class,job,stated));
        assertEquals(1,jdbc.queryForObject("select count(*) from analysis_fact where job_id=? and kind='stated_result'",Integer.class,job));
        assertEquals(0,jdbc.queryForObject("select count(*) from analysis_snapshot where job_id=? and user_id<>?",Integer.class,job,userId));
    }
    @Test void serializedDerivationBudgetFailsExplicitlyWithoutClaimingCompleteNovelty() throws Exception {
        UUID primary=note("알고리즘",null,body(0));note("구현",null,body(1));for(int i=0;i<18;i++)note("큰 문서 "+i,null,"가".repeat(50000));
        UUID job=submit(primary);worker.runOne();assertEquals("failed",jdbc.queryForObject("select status from analysis_job where id=?",String.class,job));
        assertEquals("INPUT_LIMIT",jdbc.queryForObject("select failure_code from analysis_job where id=?",String.class,job));
        assertFalse(server.requests.stream().anyMatch(r->r.has("facts")));assertEquals(0,jdbc.queryForObject("select count(*) from analysis_result where job_id=?",Integer.class,job));
        assertEquals(0,jdbc.queryForObject("select count(*) from analysis_fact where job_id=?",Integer.class,job));
    }
    @Test void conditionOrTraceInSecondDocumentDoesNotCountAsIndependentPremise() throws Exception {
        UUID primary=note("알고리즘",null,body(0));note("구현",null,body(1));server.mode="condition-only-doc";UUID job=execute(primary);
        assertEquals(2,jdbc.queryForObject("select count(distinct s.original_note_id) from analysis_result_evidence e join analysis_snapshot s on s.id=e.snapshot_id where e.job_id=?",Integer.class,job));
        assertEquals(1,jdbc.queryForObject("select count(distinct s.original_note_id) from analysis_result_evidence e join analysis_snapshot s on s.id=e.snapshot_id where e.job_id=? and e.claim_ref='premise'",Integer.class,job));
        assertEquals(0,json(call("GET","/api/candidates",null)).path("results").size());assertEquals(2,json(call("GET","/api/candidates?view=history",null)).path("results").size());
    }
    @Test void moreThan400FactsFailsExplicitlyAndDoesNotPublishPartialSuccess() throws Exception {
        UUID primary=note("알고리즘",null,body(0));note("구현",null,body(1));server.mode="overflow";UUID job=submit(primary);worker.runOne();
        assertEquals("failed",jdbc.queryForObject("select status from analysis_job where id=?",String.class,job));
        assertEquals("INPUT_LIMIT",jdbc.queryForObject("select failure_code from analysis_job where id=?",String.class,job));
        assertEquals(0,jdbc.queryForObject("select count(*) from analysis_fact where job_id=?",Integer.class,job));assertFalse(server.requests.stream().anyMatch(r->r.has("facts")));
    }
}
