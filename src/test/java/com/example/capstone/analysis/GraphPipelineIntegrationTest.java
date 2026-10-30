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
class GraphPipelineIntegrationTest extends ApiIntegrationSupport {
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    static final SyntheticPipelineServer server=new SyntheticPipelineServer();
    static final Path policy=policy();
    static Path policy() { try { Path path=Files.createTempFile("synthetic-policy-",".json");Files.writeString(path,SyntheticPipelineServer.POLICY);return path; } catch(Exception e) { throw new IllegalStateException(e); } }
    @DynamicPropertySource static void ai(DynamicPropertyRegistry p) { p.add("capstone.ai.client.base-url",()->server.uri().toString());p.add("capstone.ai.client.service-token",()->SyntheticPipelineServer.TOKEN);p.add("capstone.ai.pipeline.policy-file",policy::toString); }
    @AfterAll static void cleanup() throws Exception { server.close();Files.delete(policy); }
    @Autowired AnalysisWorkService work;
    @Autowired GraphAnalysisPipeline pipeline;
    @Autowired AnalysisWorker worker;
    @BeforeEach void reset() { server.mode="normal";server.requests.clear(); }
    UUID submit(UUID note) throws Exception { var result=call("POST","/api/notes/"+note+"/analysis",null);assertEquals(202,result.andReturn().getResponse().getStatus(),result.andReturn().getResponse().getContentAsString());return UUID.fromString(json(result).path("id").asString()); }
    void execute(UUID note) throws Exception { UUID job=submit(note);var claimed=work.claim();assertEquals(job,claimed.id());var output=pipeline.run(claimed);work.complete(claimed,output);assertEquals("succeeded",jdbc.queryForObject("select status from analysis_job where id=?",String.class,job)); }
    @Test void actualHttpStagesPublishMappedGraphClaimsAndRepeatIdentityOnce() throws Exception {
        UUID first=note("앞",null,"A>B"),second=note("뒤",null,"B>C");execute(first);
        assertEquals(1,jdbc.queryForObject("select count(*) from analysis_result where user_id=? and soundness='sound'",Integer.class,userId));
        assertEquals(3,jdbc.queryForObject("select count(distinct value->>'id') from analysis_graph_item where user_id=? and kind='concept'",Integer.class,userId));
        assertEquals(3,jdbc.queryForObject("select count(distinct claim_ref) from analysis_result_evidence where user_id=?",Integer.class,userId));
        assertTrue(server.requests.stream().anyMatch(r->r.path("context").path("noteId").asString().equals(second.toString()) && r.has("note")));
        execute(first);
        var recommendations=json(call("GET","/api/candidates",null).andExpect(status().isOk()));assertEquals(1,recommendations.path("results").size(),recommendations.toString());
        assertEquals(2,jdbc.queryForObject("select count(*) from analysis_result where user_id=?",Integer.class,userId));
        assertEquals(1,jdbc.queryForObject("select count(distinct identity_sha256) from analysis_result where user_id=?",Integer.class,userId));
        assertEquals(0,json(call("GET","/api/candidates",null,otherBearer)).path("results").size());
    }
    @Test void uncertainBridgeContradictionAndAssumptionsNeverBecomeRecommendations() throws Exception {
        UUID first=note("앞",null,"A>B");note("뒤",null,"B>C");
        for(String mode:java.util.List.of("bridge-uncertain","contradiction","assumption")) {
            server.mode=mode;execute(first);assertEquals(0,json(call("GET","/api/candidates",null)).path("results").size());
        }
        assertEquals(0,jdbc.queryForObject("select count(*) from analysis_result where user_id=? and soundness='sound'",Integer.class,userId));
    }
    @Test void finalCanonicalIdsRemainConsistentAcrossMultipleBridgeUnionsAndConditions() throws Exception {
        UUID first=note("앞",null,"A>B");note("뒤1",null,"B>C");note("뒤2",null,"B>D");server.mode="bridge-chain";execute(first);
        var ids=jdbc.queryForList("select distinct value->>'id' from analysis_graph_item where user_id=? and kind='concept'",String.class,userId);
        assertEquals(4,ids.size());
        for(String detail:jdbc.queryForList("select detail::text from analysis_result where user_id=?",String.class,userId)) {
            var path=mapper.readTree(detail).path("path");for(String key:java.util.List.of("sourceConceptId","bridgeConceptId","targetConceptId")) assertTrue(ids.contains(path.path(key).asString()));
        }
        server.mode="condition";execute(first);
        assertEquals(4,jdbc.queryForObject("select count(distinct identity_sha256) from analysis_result where user_id=?",Integer.class,userId));
        assertTrue(json(call("GET","/api/graph?scope=all",null)).path("nodes").size()>=4);
    }
    @Test void reusedAliasCannotLoseItsSourceSnapshotWhenScopeChanges() throws Exception {
        UUID first=note("앞",null,"A>B"),outside=note("뒤1",null,"B>C");note("뒤2",null,"B>D");execute(first);
        for(int i=0;i<20;i++)note("새 문서 "+i,null,"원문");execute(first);
        UUID latest=jdbc.queryForObject("select id from analysis_job where user_id=? order by created_at desc limit 1",UUID.class,userId);
        assertEquals(0,jdbc.queryForObject("select count(*) from analysis_snapshot where job_id=? and original_note_id=?",Integer.class,latest,outside));
        assertEquals(0,jdbc.queryForObject("select count(*) from analysis_graph_item where job_id=? and kind='concept' and jsonb_array_length(value->'aliases')<>1",Integer.class,latest));
        call("PUT","/api/notes/"+outside,"{\"title\":\"뒤1\",\"body\":\"변경\",\"version\":0}").andExpect(status().isOk());
        assertEquals(20,jdbc.queryForObject("select count(*) from analysis_snapshot where job_id=? and freshness='current'",Integer.class,latest));
        var graph=json(call("GET","/api/notes/"+first+"/graph",null).andExpect(status().isOk()));assertEquals(2,graph.path("concepts").size());
    }
    @Test void wrongCompositionFailsBeforeBridgeAndPersistsNoResults() throws Exception {
        UUID first=note("앞",null,"A>B");note("뒤",null,"B>C");server.mode="bad-composition";UUID job=submit(first);assertTrue(worker.runOne());
        assertEquals("failed",jdbc.queryForObject("select status from analysis_job where id=?",String.class,job));assertEquals(0,jdbc.queryForObject("select count(*) from analysis_result where job_id=?",Integer.class,job));
        assertFalse(server.requests.stream().anyMatch(r->r.has("generationProvenance")));
    }
    @Test void longUnicodeCorpusSplitsLinksWithinActualByteBudget() throws Exception {
        UUID primary=null;for(int i=0;i<20;i++) { UUID id=note("큰 문서 "+i,null,"가".repeat(50000));if(primary==null)primary=id; }
        execute(primary);
        var links=server.requests.stream().filter(r->r.has("candidates")).toList();assertTrue(links.size()>1);
        assertEquals(19,links.stream().mapToInt(r->r.path("candidates").size()).sum());
        for(var request:links) assertTrue(mapper.writeValueAsBytes(request).length<=2097152);
    }
}
