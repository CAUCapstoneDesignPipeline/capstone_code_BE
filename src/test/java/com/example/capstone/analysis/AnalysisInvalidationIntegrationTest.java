package com.example.capstone.analysis;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import com.example.capstone.analysis.client.AiResponseValidator;
import com.example.capstone.analysis.service.AnalysisWorkService;
import com.example.capstone.analysis.worker.AnalysisPipeline.PipelineOutput;
import com.example.capstone.analysis.worker.AnalysisPipeline.Work;
import com.example.capstone.support.ApiIntegrationSupport;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"capstone.ai.enabled=true","capstone.ai.pipeline.synthetic-stub=true","capstone.ai.execution.worker-enabled=false","capstone.ai.execution.poll-ms=3600000"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
class AnalysisInvalidationIntegrationTest extends ApiIntegrationSupport {
    static final java.nio.file.Path policy=policy();
    static java.nio.file.Path policy() { try { var p=java.nio.file.Files.createTempFile("invalidation-policy-",".json");java.nio.file.Files.writeString(p,com.example.capstone.analysis.support.SyntheticPipelineServer.POLICY);return p; } catch(Exception e) { throw new IllegalStateException(e); } }
    @org.springframework.test.context.DynamicPropertySource static void config(org.springframework.test.context.DynamicPropertyRegistry p) { p.add("capstone.ai.pipeline.policy-file",policy::toString); }
    @org.junit.jupiter.api.AfterAll static void cleanupPolicy() throws Exception { java.nio.file.Files.delete(policy); }

    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    @Autowired AnalysisWorkService work;
    @Test void bodyChangeInvalidatesDependenciesAndRestoredTextNeverRestoresVerificationOrReview() throws Exception {
        UUID a=note("a",null,"가"),b=note("b",null,"나"),c=note("c",null,"다");work.submit(userId,a);Work job=work.claim();
        ObjectNode r1=result(job,List.of(a,b)),r2=result(job,List.of(b,c));r2.putArray("dependsOn").add(r1.path("id").asString());
        work.complete(job,new PipelineOutput(List.of(),List.of(),List.of(r1,r2)));
        UUID id=UUID.fromString(r1.path("id").asString());
        call("POST","/api/candidates/"+id+"/review","{\"expectedRevision\":1,\"action\":\"accept\",\"reason\":null,\"learning\":\"new_understanding\",\"editedConclusion\":null,\"idempotencyKey\":\"decision\"}").andExpect(status().isCreated());
        call("PUT","/api/notes/"+a,"{\"title\":\"a\",\"body\":\"바뀐 원문\",\"version\":0}").andExpect(status().isOk());
        assertEquals(2,jdbc.queryForObject("select count(*) from analysis_result where job_id=? and freshness='stale'",Integer.class,job.id()));
        assertTrue(json(call("GET","/api/candidates",null)).path("results").isEmpty());
        call("PUT","/api/notes/"+a,"{\"title\":\"a\",\"body\":\"가\",\"version\":1}").andExpect(status().isOk());
        JsonNode detail=json(call("GET","/api/candidates/"+id,null));assertEquals("stale",detail.path("freshness").asString());assertEquals("accepted",detail.path("reviewStatus").asString());assertEquals("sound",detail.path("soundness").asString());
        assertEquals(1,jdbc.queryForObject("select count(*) from analysis_review_event where result_id=?",Integer.class,id));
        assertEquals(2L,jdbc.queryForObject("select input_revision from note where id=?",Long.class,a));
        assertEquals(1,jdbc.queryForObject("select count(*) from analysis_job where note_id=?",Integer.class,a));
        call("DELETE","/api/notes/"+a,null).andExpect(status().isConflict());
    }
    @Test void moveKeepsPublicVersionButFencesInFlightJobUsingInputRevision() throws Exception {
        UUID a=note("move",null,"가"),topic=topic("주제");work.submit(userId,a);Work claimed=work.claim();
        JsonNode moved=json(call("PUT","/api/notes/"+a+"/topic","{\"topicId\":\""+topic+"\"}").andExpect(status().isOk()));assertEquals(0,moved.path("version").asInt());
        assertEquals(1L,jdbc.queryForObject("select input_revision from note where id=?",Long.class,a));
        assertEquals("superseded",jdbc.queryForObject("select lifecycle_state from analysis_job where id=?",String.class,claimed.id()));
        assertThrows(AnalysisWorkService.LeaseLostException.class,()->work.complete(claimed,new PipelineOutput(List.of(),List.of(),List.of())));
        assertEquals(1,jdbc.queryForObject("select count(*) from analysis_job where note_id=?",Integer.class,a));
    }
    @Test void deletionBeforeResponseCannotResurrectJobAndSnapshotOnlyReferencesCanBeDeleted() throws Exception {
        UUID a=note("primary",null,"가"),b=note("candidate",null,"나");work.submit(userId,a);Work claimed=work.claim();
        call("DELETE","/api/notes/"+b,null).andExpect(status().isNoContent());
        assertEquals(1,jdbc.queryForObject("select count(*) from analysis_snapshot where job_id=? and original_note_id=? and note_id is null",Integer.class,claimed.id(),b));
        assertThrows(AnalysisWorkService.LeaseLostException.class,()->work.complete(claimed,new PipelineOutput(List.of(),List.of(),List.of())));
        call("DELETE","/api/notes/"+a,null).andExpect(status().isNoContent());
        assertEquals(0,jdbc.queryForObject("select count(*) from analysis_job where id=?",Integer.class,claimed.id()));
        assertEquals(0,jdbc.queryForObject("select count(*) from analysis_snapshot where job_id=?",Integer.class,claimed.id()));
    }
    @Test void topicRenameAndRemovalAdvanceIndependentRevisionWithoutSchedulingAnalysis() throws Exception {
        UUID topic=topic("old"),a=note("topic-note",topic,"가");work.submit(userId,a);Work claimed=work.claim();
        call("PATCH","/api/topics/"+topic,"{\"name\":\"new\"}").andExpect(status().isOk());
        assertEquals(1L,jdbc.queryForObject("select input_revision from note where id=?",Long.class,a));
        call("DELETE","/api/topics/"+topic,null).andExpect(status().isNoContent());
        assertEquals(2L,jdbc.queryForObject("select input_revision from note where id=?",Long.class,a));
        assertEquals(0,jdbc.queryForObject("select version from note where id=?",Integer.class,a));
        assertEquals("superseded",jdbc.queryForObject("select lifecycle_state from analysis_job where id=?",String.class,claimed.id()));
        assertEquals(1,jdbc.queryForObject("select count(*) from analysis_job where note_id=?",Integer.class,a));
    }
    @Test void explicitNewPolicyAdvancesInputsAndInvalidatesPreviousPolicyWithoutAutomaticEnqueue() throws Exception {
        UUID a=note("policy",null,"가");work.submit(userId,a,AiResponseValidator.hash("policy-one"));var old=work.claim();work.complete(old,new PipelineOutput(List.of(),List.of(),List.of()));
        work.submit(userId,a,AiResponseValidator.hash("policy-two"));
        assertEquals(1L,jdbc.queryForObject("select input_revision from note where id=?",Long.class,a));
        assertEquals("stale",jdbc.queryForObject("select freshness from analysis_snapshot where job_id=? and original_note_id=?",String.class,old.id(),a));
        assertEquals(2,jdbc.queryForObject("select count(*) from analysis_job where note_id=?",Integer.class,a));
        var fresh=work.claim();work.fail(fresh,"TEST_END");
    }
    @Test void restartedWorkerRejectsQueuedWorkFromPreviousPolicyBeforeExternalCalls() throws Exception {
        UUID a=note("old policy",null,"가");work.submit(userId,a,AiResponseValidator.hash("old"));Work claimed=work.claim();
        assertFalse(work.current(claimed,AiResponseValidator.hash("new")));
        assertEquals("superseded",jdbc.queryForObject("select lifecycle_state from analysis_job where id=?",String.class,claimed.id()));
        assertEquals(0,jdbc.queryForObject("select count(*) from analysis_stage where job_id=?",Integer.class,claimed.id()));
    }
    private ObjectNode result(Work job,List<UUID> notes) {
        ObjectNode r=mapper.createObjectNode();UUID id=UUID.randomUUID();r.put("id",id.toString());r.put("ref",id.toString());r.put("identitySha256",AiResponseValidator.hash(id.toString()));r.put("kind","derivation");r.put("conclusion","합성결론");r.putArray("conditions");r.putArray("trace");r.putObject("detail");r.put("derivationStatus","conclusion");r.put("soundness","sound");r.putArray("dependsOn");
        var novelty=r.putArray("noveltyNoteIds");var evidence=r.putArray("evidence");
        for(var doc:job.documents()) if(notes.contains(UUID.fromString(doc.path("noteId").asString()))) {
            novelty.add(doc.path("noteId").asString());var e=evidence.addObject();e.set("snapshotId",doc.path("snapshotId"));e.put("claimRef","premise");e.put("startOffset",0);e.put("endOffset",1);e.put("quote",doc.path("body").asString());
        }
        return r;
    }
}
