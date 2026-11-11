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
import com.example.capstone.analysis.service.AnalysisWorkService;
import com.example.capstone.analysis.worker.AnalysisPipeline.PipelineOutput;
import com.example.capstone.analysis.worker.AnalysisPipeline.Work;
import com.example.capstone.support.ApiIntegrationSupport;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 작업은 자기 snapshot에 든 노트가 바뀌었을 때만 대체된다. */
@SpringBootTest(properties={"capstone.ai.enabled=true","capstone.ai.pipeline.synthetic-stub=true","capstone.ai.execution.worker-enabled=false","capstone.ai.execution.poll-ms=3600000"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
class AnalysisFenceScopeIntegrationTest extends ApiIntegrationSupport {
    static final java.nio.file.Path policy=policy();
    static java.nio.file.Path policy() { try { var p=java.nio.file.Files.createTempFile("fence-policy-",".json");java.nio.file.Files.writeString(p,com.example.capstone.analysis.support.SyntheticPipelineServer.POLICY);return p; } catch(Exception e) { throw new IllegalStateException(e); } }
    @org.springframework.test.context.DynamicPropertySource static void config(org.springframework.test.context.DynamicPropertyRegistry p) { p.add("capstone.ai.pipeline.policy-file",policy::toString); }
    @org.junit.jupiter.api.AfterAll static void cleanupPolicy() throws Exception { java.nio.file.Files.delete(policy); }
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    @Autowired AnalysisWorkService work;

    @Test void queuedJobsOfSameUserBothSucceedAfterEarlierJobPublishesGraph() {
        UUID a=noteQuietly("first","가"),b=noteQuietly("second","나");
        JsonNode first=work.submit(userId,a),second=work.submit(userId,b);
        assertTrue(first.path("graph_revision").isNull());assertTrue(second.path("graph_revision").isNull());
        Work claimedFirst=work.claim();assertEquals(UUID.fromString(first.path("id").asString()),claimedFirst.id());
        work.complete(claimedFirst,new PipelineOutput(List.of(),List.of(concept(claimedFirst,a)),List.of()));
        assertEquals(1L,jdbc.queryForObject("select graph_revision from app_user where id=?",Long.class,userId));
        Work claimedSecond=work.claim();assertEquals(UUID.fromString(second.path("id").asString()),claimedSecond.id());
        assertEquals(1L,claimedSecond.graphRevision());
        work.complete(claimedSecond,new PipelineOutput(List.of(),List.of(),List.of()));
        assertEquals(2,jdbc.queryForObject("select count(*) from analysis_job where user_id=? and lifecycle_state='succeeded'",Integer.class,userId));
    }
    @Test void editingNoteOutsideSnapshotDoesNotEndRunningJob() throws Exception {
        UUID a=note("inside",null,"가");work.submit(userId,a);Work claimed=work.claim();
        UUID outside=note("outside",null,"밖");
        call("PUT","/api/notes/"+outside,"{\"title\":\"outside\",\"body\":\"바뀐 밖\",\"version\":0}").andExpect(status().isOk());
        assertEquals("running",jdbc.queryForObject("select lifecycle_state from analysis_job where id=?",String.class,claimed.id()));
        assertTrue(work.current(claimed));
        work.complete(claimed,new PipelineOutput(List.of(),List.of(),List.of()));
        assertEquals("succeeded",jdbc.queryForObject("select lifecycle_state from analysis_job where id=?",String.class,claimed.id()));
        assertEquals(0L,jdbc.queryForObject("select graph_revision from app_user where id=?",Long.class,userId));
    }
    @Test void editingNoteInsideSnapshotStillSupersedesWithoutPublishing() throws Exception {
        UUID a=note("primary",null,"가"),b=note("candidate",null,"나");work.submit(userId,a);Work claimed=work.claim();
        call("PUT","/api/notes/"+b,"{\"title\":\"candidate\",\"body\":\"바뀐 근거\",\"version\":0}").andExpect(status().isOk());
        assertEquals("superseded",jdbc.queryForObject("select lifecycle_state from analysis_job where id=?",String.class,claimed.id()));
        assertThrows(AnalysisWorkService.LeaseLostException.class,()->work.complete(claimed,new PipelineOutput(List.of(),List.of(concept(claimed,a)),List.of())));
        assertEquals(0,jdbc.queryForObject("select count(*) from analysis_graph_item where job_id=?",Integer.class,claimed.id()));
    }
    private UUID noteQuietly(String title,String body) { try { return note(title,null,body); } catch(Exception e) { throw new IllegalStateException(e); } }
    private ObjectNode concept(Work job,UUID note) {
        JsonNode doc=job.documents().stream().filter(d->d.path("noteId").asString().equals(note.toString())).findFirst().orElseThrow();
        ObjectNode item=mapper.createObjectNode();item.put("id",UUID.randomUUID().toString());item.set("snapshotId",doc.path("snapshotId"));item.put("kind","concept");item.put("ref","c1");
        item.putObject("value").put("id",UUID.randomUUID().toString()).put("label","개념").putArray("mentions");return item;
    }
}
