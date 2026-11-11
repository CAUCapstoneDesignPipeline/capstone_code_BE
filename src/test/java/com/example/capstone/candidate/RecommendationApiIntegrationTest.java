package com.example.capstone.candidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import com.example.capstone.analysis.client.AiCallException;
import com.example.capstone.analysis.client.AiResponseValidator;
import com.example.capstone.analysis.service.AnalysisWorkService;
import com.example.capstone.analysis.support.ScriptedAiServer;
import com.example.capstone.analysis.worker.AnalysisPipeline;
import com.example.capstone.analysis.worker.AnalysisPipeline.PipelineOutput;
import com.example.capstone.analysis.worker.AnalysisPipeline.Work;
import com.example.capstone.analysis.worker.AnalysisWorker;
import com.example.capstone.support.ApiIntegrationSupport;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"capstone.ai.enabled=true","capstone.ai.execution.budget-microunits=1000","capstone.ai.execution.currency=KRW","capstone.ai.execution.required-profiles=fixture","capstone.ai.execution.worker-enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
@Import(RecommendationApiIntegrationTest.PipelineConfig.class)
class RecommendationApiIntegrationTest extends ApiIntegrationSupport {
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
    @BeforeEach void reset() { action.set(w->EMPTY);jdbc.update("update note set analysis_rerequested=false where analysis_rerequested"); }

    @Test void saveRequestPollShowsAtMostFiveCardsAnchoredToRequestedNote() throws Exception {
        UUID a=note("재시도 정책",null,"클라이언트는 응답 시간이 초과되면 요청을 재전송한다."),b=note("멱등성",null,"멱등한 연산은 여러 번 실행해도 결과가 같다."),c=note("인덱스",null,"인덱스는 조회를 빠르게 한다.");
        action.set(w->{
            var results=new ArrayList<JsonNode>();
            for(int i=0;i<6;i++) results.add(result(w,List.of(a,b),"결론"+i,false));
            results.add(result(w,List.of(b,c),"요청 노트가 없는 결론",false));
            results.add(result(w,List.of(a,c),"가설 결론",true));
            return new PipelineOutput(List.of(),List.of(),results);
        });
        JsonNode job=request(a);assertEquals("pending",job.path("status").asString());assertTrue(job.path("outcome").isNull());assertTrue(job.path("recommendations").isEmpty());
        assertEquals(job.path("id").asString(),request(a).path("id").asString());
        ready();assertTrue(worker.runOne());
        JsonNode done=json(call("GET","/api/recommendation-jobs/"+job.path("id").asString(),null).andExpect(status().isOk()));
        assertEquals("completed",done.path("status").asString());assertEquals("RECOMMENDED",done.path("outcome").asString());assertFalse(done.path("completedAt").isNull());
        assertEquals(5,done.path("recommendations").size());
        for(JsonNode card:done.path("recommendations")) {
            assertEquals(a.toString(),card.path("evidenceA").path("noteId").asString());assertEquals("재시도 정책",card.path("evidenceA").path("noteTitle").asString());
            assertEquals(b.toString(),card.path("evidenceB").path("noteId").asString());
            assertEquals("클라이언트는 응답 시간이 초과되면 요청을 재전송한다.",card.path("evidenceA").path("quote").asString());
            assertTrue(card.path("externalKnowledge").isNull());assertEquals(List.of("재시도","멱등성",card.path("conclusion").asString()),mapper.treeToValue(card.path("relatedKeywords"),List.class));
            assertEquals(1,card.path("revision").asInt());assertEquals("current",card.path("freshness").asString());
        }
    }
    @Test void ownershipAndAuthenticationAreEnforced() throws Exception {
        UUID a=note("mine",null,"내 문장이다."),b=note("other",null,"다른 문장이다.");
        action.set(w->new PipelineOutput(List.of(),List.of(),List.of(result(w,List.of(a,b),"결론",false))));
        String job=request(a).path("id").asString();ready();worker.runOne();
        String card=json(call("GET","/api/recommendation-jobs/"+job,null)).path("recommendations").get(0).path("id").asString();
        call("GET","/api/recommendation-jobs/"+job,null,otherBearer).andExpect(status().isNotFound());
        call("POST","/api/notes/"+a+"/recommendation-jobs",null,otherBearer).andExpect(status().isNotFound());
        call("POST","/api/recommendations/"+card+"/feedback","{\"action\":\"understood\"}",otherBearer).andExpect(status().isNotFound());
        call("GET","/api/recommendation-jobs/"+job,null,"invalid").andExpect(status().isUnauthorized());
        call("GET","/api/recommendation-jobs/"+UUID.randomUUID(),null).andExpect(status().isNotFound());
        call("GET","/api/recommendation-jobs/not-a-uuid",null).andExpect(status().isBadRequest());
    }
    @Test void feedbackRecordsGraphDecisionWithoutChangingPageBody() throws Exception {
        UUID a=note("a",null,"첫 근거 문장이다."),b=note("b",null,"둘째 근거 문장이다.");
        action.set(w->new PipelineOutput(List.of(),List.of(),List.of(result(w,List.of(a,b),"이해",false),result(w,List.of(a,b),"앎",false),result(w,List.of(a,b),"모름",false))));
        String job=request(a).path("id").asString();ready();worker.runOne();
        JsonNode cards=json(call("GET","/api/recommendation-jobs/"+job,null)).path("recommendations");assertEquals(3,cards.size());
        String understood=cards.get(0).path("id").asString(),known=cards.get(1).path("id").asString(),confused=cards.get(2).path("id").asString();
        call("POST","/api/recommendations/"+understood+"/feedback","{\"action\":\"understood\"}").andExpect(status().isNoContent());
        call("POST","/api/recommendations/"+understood+"/feedback","{\"action\":\"understood\"}").andExpect(status().isNoContent());
        assertEquals(409,call("POST","/api/recommendations/"+understood+"/feedback","{\"action\":\"known\"}").andReturn().getResponse().getStatus());
        assertEquals("accepted",reviewStatus(understood));assertEquals(1,events(understood));
        call("POST","/api/recommendations/"+known+"/feedback","{\"action\":\"known\"}").andExpect(status().isNoContent());assertEquals("rejected",reviewStatus(known));
        call("POST","/api/recommendations/"+confused+"/feedback","{\"action\":\"confused\"}").andExpect(status().isNoContent());
        assertEquals("unreviewed",reviewStatus(confused));assertEquals(0,events(confused));
        call("POST","/api/recommendations/"+confused+"/feedback","{\"action\":\"confused\"}").andExpect(status().isNoContent());
        call("POST","/api/recommendations/"+confused+"/feedback","{\"action\":\"understood\"}").andExpect(status().isNoContent());assertEquals("accepted",reviewStatus(confused));
        JsonNode invalid=json(call("POST","/api/recommendations/"+known+"/feedback","{\"action\":\"maybe\"}").andExpect(status().isBadRequest()));
        assertEquals("action",invalid.path("error").path("details").path("fields").get(0).path("field").asString());
        call("POST","/api/recommendations/"+known+"/feedback","{}").andExpect(status().isBadRequest());
        assertEquals("첫 근거 문장이다.",jdbc.queryForObject("select body from note where id=?",String.class,a));
        assertEquals(0,jdbc.queryForObject("select version from note where id=?",Integer.class,a));
        assertTrue(json(call("GET","/api/recommendation-jobs/"+job,null)).path("recommendations").isEmpty());
    }
    @Test void analysisFailureAndNoRecommendationUseDifferentOutcomes() throws Exception {
        UUID a=note("fail",null,"실패할 문장이다."),b=note("none",null,"추천이 없는 문장이다.");
        action.set(w->{ throw new AiCallException(AiCallException.Reason.INVALID_RESPONSE); });
        String failed=request(a).path("id").asString();ready();worker.runOne();
        JsonNode failure=json(call("GET","/api/recommendation-jobs/"+failed,null));
        assertEquals("completed",failure.path("status").asString());assertEquals("RECOMMENDATION_FAILED",failure.path("outcome").asString());
        assertFalse(failure.toString().contains("INVALID_RESPONSE"));
        action.set(w->EMPTY);
        String empty=request(b).path("id").asString();ready();worker.runOne();
        JsonNode none=json(call("GET","/api/recommendation-jobs/"+empty,null));
        assertEquals("completed",none.path("status").asString());assertEquals("NO_RECOMMENDATIONS",none.path("outcome").asString());
        UUID c=note("timeout",null,"시간 초과 문장이다.");String timeout=request(c).path("id").asString();
        jdbc.update("update analysis_job set deadline=clock_timestamp()-interval '1 second' where id=?",UUID.fromString(timeout));work.expire();
        assertEquals("RECOMMENDATION_FAILED",json(call("GET","/api/recommendation-jobs/"+timeout,null)).path("outcome").asString());
    }
    @Test void supersededJobFollowsContinuationOfSameNote() throws Exception {
        UUID a=note("requested",null,"요청한 문장이다."),b=note("edited",null,"고칠 문장이다.");
        String original=request(a).path("id").asString();Work first=work.claim();assertEquals(UUID.fromString(original),first.id());
        call("PUT","/api/notes/"+b,"{\"title\":\"edited\",\"body\":\"고친 문장이다.\",\"version\":0}").andExpect(status().isOk());
        assertEquals("pending",json(call("GET","/api/recommendation-jobs/"+original,null)).path("status").asString());
        assertTrue(work.resume(null));Work resumed=work.claim();
        assertEquals("pending",json(call("GET","/api/recommendation-jobs/"+original,null)).path("status").asString());
        work.complete(resumed,new PipelineOutput(List.of(),List.of(),List.of(result(resumed,List.of(a,b),"이어 실행 결론",false))));
        JsonNode followed=json(call("GET","/api/recommendation-jobs/"+original,null));
        assertEquals(original,followed.path("id").asString());assertEquals("RECOMMENDED",followed.path("outcome").asString());
        assertEquals("고친 문장이다.",followed.path("recommendations").get(0).path("evidenceB").path("quote").asString());
    }
    @Test void aiUnavailableRejectsRequestWithoutCreatingJob() throws Exception {
        UUID a=note("off",null,"꺼진 문장이다.");
        server.enqueue(new ScriptedAiServer.Reply(503,"{\"error\":{\"code\":\"UNAVAILABLE\"}}",Map.of()));
        JsonNode error=json(call("POST","/api/notes/"+a+"/recommendation-jobs",null).andExpect(status().isServiceUnavailable()));
        assertEquals("AI_UNAVAILABLE",error.path("error").path("code").asString());
        assertEquals(0,jdbc.queryForObject("select count(*) from analysis_job where note_id=?",Integer.class,a));
    }
    @Test void reanalysisKeepsCardIdAndRaisesRevisionOnlyWhenContentChanges() throws Exception {
        UUID a=note("a",null,"요청 노트 문장이다."),b=note("b",null,"다른 노트 문장이다.");
        List<String> path=List.of("재시도","멱등성","중복 방지");
        JsonNode first=run(a,w->List.of(result(w,List.of(a,b),"결론",false,path),result(w,List.of(a,b),"다른 경로",false,List.of("재시도","캐시","중복 방지"))));
        assertEquals(2,first.size());assertNotEquals(first.get(0).path("id").asString(),first.get(1).path("id").asString());
        String id=(first.get(0).path("conclusion").asString().equals("결론")?first.get(0):first.get(1)).path("id").asString();
        JsonNode same=run(a,w->List.of(result(w,List.of(a,b),"결론",false,List.of(" 재시도","멱등성 ","중복  방지"))));
        assertEquals(id,same.get(0).path("id").asString());assertEquals(1,same.get(0).path("revision").asInt());assertEquals("current",same.get(0).path("freshness").asString());
        JsonNode changed=run(a,w->List.of(result(w,List.of(a,b),"바뀐 결론",false,path)));
        assertEquals(id,changed.get(0).path("id").asString());assertEquals(2,changed.get(0).path("revision").asInt());assertEquals("바뀐 결론",changed.get(0).path("conclusion").asString());
    }
    @Test void knownFingerprintIsNotRecommendedAgain() throws Exception {
        UUID a=note("a",null,"요청 노트 문장이다."),b=note("b",null,"다른 노트 문장이다.");
        JsonNode first=run(a,w->List.of(result(w,List.of(a,b),"이미 아는 결론",false)));
        call("POST","/api/recommendations/"+first.get(0).path("id").asString()+"/feedback","{\"action\":\"known\"}").andExpect(status().isNoContent());
        action.set(w->new PipelineOutput(List.of(),List.of(),List.of(result(w,List.of(a,b),"이미 아는 결론",false))));
        String job=request(a).path("id").asString();ready();worker.runOne();
        JsonNode again=json(call("GET","/api/recommendation-jobs/"+job,null));
        assertTrue(again.path("recommendations").isEmpty());assertEquals("NO_RECOMMENDATIONS",again.path("outcome").asString());
    }
    @Test void staleAndEvidenceLostCardsRejectEveryAnswerWithoutRecording() throws Exception {
        UUID a=note("a",null,"남길 문장이다. 지울 문장이다."),b=note("b",null,"다른 노트 문장이다.");
        List<String> kept=List.of("가","나","다"),dropped=List.of("가","라","다");
        action.set(w->new PipelineOutput(List.of(),List.of(),List.of(result(w,List.of(a,b),"남는 결론",false,kept),result(w,List.of(a,b),"사라질 결론",false,dropped))));
        String old=request(a).path("id").asString();ready();worker.runOne();
        run(a,w->List.of(result(w,List.of(a,b),"남는 결론",false,kept)));
        JsonNode cards=json(call("GET","/api/recommendation-jobs/"+old,null)).path("recommendations");
        JsonNode stale=null,current=null;for(JsonNode card:cards) if(card.path("conclusion").asString().equals("사라질 결론")) stale=card;else current=card;
        assertEquals("stale",stale.path("freshness").asString());assertEquals("current",current.path("freshness").asString());
        for(String answer:List.of("understood","known","confused")) {
            JsonNode error=json(call("POST","/api/recommendations/"+stale.path("id").asString()+"/feedback","{\"action\":\""+answer+"\"}").andExpect(status().isConflict()));
            assertEquals("RECOMMENDATION_STALE",error.path("error").path("code").asString());
        }
        assertEquals(0,jdbc.queryForObject("select count(*) from recommendation_feedback f join analysis_result r on r.id=f.result_id where r.recommendation_id=?",Integer.class,UUID.fromString(stale.path("id").asString())));
        call("PUT","/api/notes/"+a,"{\"title\":\"a\",\"body\":\"남김 없이 바꾼 본문이다.\",\"version\":0}").andExpect(status().isOk());
        JsonNode lost=json(call("GET","/api/recommendation-jobs/"+old,null)).path("recommendations");
        for(JsonNode card:lost) assertEquals("evidence_lost",card.path("freshness").asString());
        assertEquals("RECOMMENDATION_STALE",json(call("POST","/api/recommendations/"+current.path("id").asString()+"/feedback","{\"action\":\"understood\"}").andExpect(status().isConflict())).path("error").path("code").asString());
        assertEquals("unreviewed",reviewStatus(current.path("id").asString()));
    }
    private JsonNode run(UUID note,Function<Work,List<JsonNode>> results) throws Exception {
        action.set(w->new PipelineOutput(List.of(),List.of(),results.apply(w)));
        String job=request(note).path("id").asString();ready();assertTrue(worker.runOne());
        return json(call("GET","/api/recommendation-jobs/"+job,null)).path("recommendations");
    }
    private JsonNode request(UUID note) throws Exception { ready();return json(call("POST","/api/notes/"+note+"/recommendation-jobs",null).andExpect(status().isAccepted())); }
    private void ready() { server.enqueue(new ScriptedAiServer.Reply(200,"{\"status\":\"ready\",\"schemaVersion\":\"1.0\",\"profileVersions\":[\"fixture\"]}",Map.of())); }
    private String reviewStatus(String id) { return jdbc.queryForObject("select review_status from analysis_result where recommendation_id=? order by created_at desc,id desc limit 1",String.class,UUID.fromString(id)); }
    private int events(String id) { return jdbc.queryForObject("select count(*) from analysis_review_event e join analysis_result r on r.id=e.result_id where r.recommendation_id=?",Integer.class,UUID.fromString(id)); }
    private ObjectNode result(Work job,List<UUID> notes,String conclusion,boolean hypothesis) { return result(job,notes,conclusion,hypothesis,List.of("재시도","멱등성",conclusion)); }
    private ObjectNode result(Work job,List<UUID> notes,String conclusion,boolean hypothesis,List<String> labels) {
        ObjectNode r=mapper.createObjectNode();UUID id=UUID.randomUUID();r.put("id",id.toString());r.put("ref",id.toString());r.put("identitySha256",AiResponseValidator.hash(id.toString()));r.put("kind","graph");r.put("conclusion",conclusion);r.putArray("conditions");r.putArray("trace");
        ObjectNode detail=r.putObject("detail");detail.put("hypothesis",hypothesis);var concepts=detail.putObject("path").putArray("concepts");
        for(String label:labels) concepts.addObject().put("conceptId",UUID.randomUUID().toString()).put("label",label);
        r.put("derivationStatus","conclusion");r.put("soundness","sound");r.putArray("dependsOn");
        var novelty=r.putArray("noveltyNoteIds");var evidence=r.putArray("evidence");
        for(UUID note:notes) {
            JsonNode doc=job.documents().stream().filter(d->d.path("noteId").asString().equals(note.toString())).findFirst().orElseThrow();
            novelty.add(note.toString());var e=evidence.addObject();e.set("snapshotId",doc.path("snapshotId"));e.put("claimRef","c"+notes.indexOf(note));e.put("startOffset",0);e.put("endOffset",doc.path("body").asString().length());e.put("quote",doc.path("body").asString());
        }
        return r;
    }
}
