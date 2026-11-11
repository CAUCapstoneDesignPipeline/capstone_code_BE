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

/** 다른 노트 수정으로 대체된 명시 요청은 추가 POST 없이 최신 내용으로 한 번 이어 실행된다. */
@SpringBootTest(properties={"capstone.ai.enabled=true","capstone.ai.pipeline.synthetic-stub=true","capstone.ai.execution.worker-enabled=false","capstone.ai.execution.poll-ms=3600000"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
class AnalysisResumeIntegrationTest extends ApiIntegrationSupport {
    static final java.nio.file.Path policy=policy();
    static java.nio.file.Path policy() { try { var p=java.nio.file.Files.createTempFile("resume-policy-",".json");java.nio.file.Files.writeString(p,com.example.capstone.analysis.support.SyntheticPipelineServer.POLICY);return p; } catch(Exception e) { throw new IllegalStateException(e); } }
    @org.springframework.test.context.DynamicPropertySource static void config(org.springframework.test.context.DynamicPropertyRegistry p) { p.add("capstone.ai.pipeline.policy-file",policy::toString); }
    @org.junit.jupiter.api.AfterAll static void cleanupPolicy() throws Exception { java.nio.file.Files.delete(policy); }
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    @Autowired AnalysisWorkService work;

    static final PipelineOutput EMPTY=new PipelineOutput(List.of(),List.of(),List.of());
    // 이어 실행 대상은 모든 사용자에서 고르므로 앞 테스트의 표시를 지운다.
    @org.junit.jupiter.api.BeforeEach void isolate() { jdbc.update("update note set analysis_rerequested=false where analysis_rerequested"); }

    @Test void supersededRequestResumesOnceWithLatestContentWithoutAnotherPost() throws Exception {
        UUID a=note("requested",null,"가"),b=note("edited",null,"나");work.submit(userId,a);Work first=work.claim();
        put(b,"바뀐 나",0);
        assertEquals("superseded",state(first.id()));assertTrue(flag(a));assertEquals(1,streak(a));
        JsonNode pending=json(call("GET","/api/notes/"+a+"/analysis",null).andExpect(status().isOk())).path("job");
        assertEquals("pending",pending.path("status").asString());assertEquals(first.id().toString(),pending.path("id").asString());
        assertNull(work.claim());assertTrue(work.resume(null));assertFalse(flag(a));
        Work resumed=work.claim();assertNotEquals(first.id(),resumed.id());assertEquals(a,resumed.noteId());
        JsonNode edited=resumed.documents().stream().filter(d->d.path("noteId").asString().equals(b.toString())).findFirst().orElseThrow();
        assertEquals("바뀐 나",edited.path("body").asString());
        work.complete(resumed,EMPTY);
        assertEquals("succeeded",json(call("GET","/api/notes/"+a+"/analysis",null)).path("job").path("status").asString());
        assertEquals(0,streak(a));assertFalse(work.resume(null));
        assertEquals(2,jdbc.queryForObject("select count(*) from analysis_job where note_id=?",Integer.class,a));
    }
    @Test void thirdConsecutiveSupersessionStopsResuming() throws Exception {
        UUID a=note("requested",null,"가"),b=note("edited",null,"나");work.submit(userId,a);
        for(int round=0;round<3;round++) { Work running=work.claim();put(b,"나"+round,round);if(round<2) assertTrue(work.resume(null)); }
        assertFalse(flag(a));assertEquals(3,streak(a));assertFalse(work.resume(null));
        assertEquals(3,jdbc.queryForObject("select count(*) from analysis_job where note_id=? and lifecycle_state='superseded'",Integer.class,a));
        assertEquals("failed",json(call("GET","/api/notes/"+a+"/analysis",null)).path("job").path("status").asString());
        work.submit(userId,a);assertEquals(0,streak(a));
    }
    @Test void failedTimedOutAndCancelledWorkIsNeverResumed() throws Exception {
        UUID a=note("failed",null,"가"),c=note("timeout",null,"다"),d=note("cancelled",null,"라");
        work.submit(userId,a);work.fail(work.claim(),"INVALID_RESPONSE");
        work.submit(userId,c);jdbc.update("update analysis_job set deadline=clock_timestamp()-interval '1 second' where note_id=?",c);work.expire();
        work.submit(userId,d);Work running=work.claim();put(a,"고친 가",0);
        assertEquals("superseded",state(running.id()));assertTrue(flag(d));
        work.cancelActive();
        assertFalse(flag(d));assertFalse(work.resume(null));
        assertEquals(1,jdbc.queryForObject("select count(*) from analysis_job where note_id=?",Integer.class,a));
        assertEquals(1,jdbc.queryForObject("select count(*) from analysis_job where note_id=?",Integer.class,c));
        assertEquals(1,jdbc.queryForObject("select count(*) from analysis_job where note_id=?",Integer.class,d));
    }
    @Test void explicitPostOnMarkedNoteKeepsSingleActiveJob() throws Exception {
        UUID a=note("requested",null,"가"),b=note("edited",null,"나");work.submit(userId,a);Work first=work.claim();put(b,"바뀐 나",0);assertTrue(flag(a));
        JsonNode posted=work.submit(userId,a);assertFalse(flag(a));assertFalse(work.resume(null));
        assertEquals(1,jdbc.queryForObject("select count(*) from analysis_job where note_id=? and lifecycle_state in ('queued','running')",Integer.class,a));
        assertEquals(posted.path("id").asString(),work.submit(userId,a).path("id").asString());
        assertNotEquals(first.id().toString(),posted.path("id").asString());
    }
    private void put(UUID note,String body,int version) throws Exception {
        call("PUT","/api/notes/"+note,mapper.writeValueAsString(java.util.Map.of("title",jdbc.queryForObject("select title from note where id=?",String.class,note),"body",body,"version",version))).andExpect(status().isOk());
    }
    private String state(UUID job) { return jdbc.queryForObject("select lifecycle_state from analysis_job where id=?",String.class,job); }
    private boolean flag(UUID note) { return jdbc.queryForObject("select analysis_rerequested from note where id=?",Boolean.class,note); }
    private int streak(UUID note) { return jdbc.queryForObject("select analysis_supersede_streak from note where id=?",Integer.class,note); }
}
