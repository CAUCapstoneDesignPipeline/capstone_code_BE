package com.example.capstone.candidate;

import java.nio.charset.StandardCharsets;
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
import com.example.capstone.analysis.client.AiResponseValidator;
import com.example.capstone.analysis.service.ExternalKnowledgeService;
import com.example.capstone.analysis.support.ScriptedAiServer;
import com.example.capstone.analysis.worker.AnalysisPipeline;
import com.example.capstone.analysis.worker.AnalysisPipeline.PipelineOutput;
import com.example.capstone.analysis.worker.AnalysisPipeline.Work;
import com.example.capstone.analysis.worker.AnalysisWorker;
import com.example.capstone.support.ApiIntegrationSupport;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 외부 지식은 분석 뒤 화면에 보일 카드에만 따로 부르고, 결과가 없거나 실패해도 추천 작업의 outcome은 그대로다. */
@SpringBootTest(properties={"capstone.ai.execution.readiness-cache=0s","capstone.ai.enabled=true","capstone.ai.execution.budget-microunits=1000","capstone.ai.execution.currency=KRW",
        "capstone.ai.execution.required-profiles=fixture","capstone.ai.execution.worker-enabled=false","capstone.ai.client.max-attempts=1",
        "capstone.ai.external-knowledge.enabled=true","capstone.ai.external-knowledge.max-attempts=2"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
@Import(RecommendationExternalKnowledgeIntegrationTest.PipelineConfig.class)
class RecommendationExternalKnowledgeIntegrationTest extends ApiIntegrationSupport {
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    static final ScriptedAiServer server=server();
    static ScriptedAiServer server() { try { return new ScriptedAiServer("synthetic-token-only",2097152); } catch(Exception e) { throw new IllegalStateException(); } }
    static final AtomicReference<Function<Work,PipelineOutput>> action=new AtomicReference<>(work->new PipelineOutput(List.of(),List.of(),List.of()));
    @TestConfiguration static class PipelineConfig { @Bean @Primary AnalysisPipeline pipeline() { return work->action.get().apply(work); } }
    @DynamicPropertySource static void ai(DynamicPropertyRegistry p) {
        p.add("capstone.ai.client.base-url",()->server.baseUri().toString());p.add("capstone.ai.client.service-token",()->"synthetic-token-only");
    }
    @Autowired AnalysisWorker worker;
    @Autowired ExternalKnowledgeService external;
    @BeforeEach void reset() { jdbc.update("delete from recommendation_external_knowledge"); }

    @Test void foundSourceFillsCardOnceAndIsReusedForSameContent() throws Exception {
        UUID a=note("재시도 정책",null,"클라이언트는 응답 시간이 초과되면 요청을 재전송한다."),b=note("멱등성",null,"멱등한 연산은 여러 번 실행해도 결과가 같다.");
        String job=analyze(a,w->List.of(result(w,List.of(a,b),"쓰기 요청을 멱등하게 설계해야 한다.")));
        assertTrue(card(job).path("externalKnowledge").isNull(),"부르기 전에는 null");
        reply(found("PUT·DELETE는 멱등 메서드다.","https://www.rfc-editor.org/rfc/rfc9110"));
        assertTrue(external.runOne());
        JsonNode sent=mapper.readTree(lastExternalRequest().body());
        assertEquals("쓰기 요청을 멱등하게 설계해야 한다.",sent.path("claim").path("conclusion").asString());
        assertEquals("A",sent.path("claim").path("evidence").get(0).path("label").asString());
        assertEquals("클라이언트는 응답 시간이 초과되면 요청을 재전송한다.",sent.path("claim").path("evidence").get(0).path("text").asString());
        assertEquals("멱등한 연산은 여러 번 실행해도 결과가 같다.",sent.path("claim").path("evidence").get(1).path("text").asString());
        assertFalse(sent.toString().contains("\"body\""),"노트 본문 전체는 보내지 않는다");
        JsonNode filled=card(job).path("externalKnowledge");
        assertEquals("PUT·DELETE는 멱등 메서드다.",filled.path("summary").asString());assertEquals("https://www.rfc-editor.org/rfc/rfc9110",filled.path("sourceUrl").asString());
        assertFalse(external.runOne(),"같은 카드를 다시 조회해도 AI를 다시 부르지 않는다");card(job);assertFalse(external.runOne());
        String again=analyze(a,w->List.of(result(w,List.of(a,b),"쓰기 요청을 멱등하게 설계해야 한다.")));
        assertFalse(external.runOne(),"같은 지문·같은 내용의 새 결과는 저장한 답을 쓴다");
        assertEquals("https://www.rfc-editor.org/rfc/rfc9110",card(again).path("externalKnowledge").path("sourceUrl").asString());
    }
    @Test void noneErrorAndDisabledLeaveNullWithoutChangingOutcome() throws Exception {
        UUID a=note("a",null,"요청 노트 문장이다."),b=note("b",null,"다른 노트 문장이다.");
        String none=analyze(a,w->List.of(result(w,List.of(a,b),"출처가 없는 결론")));
        reply(body("none",null,null));assertTrue(external.runOne());
        assertTrue(card(none).path("externalKnowledge").isNull());assertFalse(external.runOne());
        String failed=analyze(a,w->List.of(result(w,List.of(a,b),"꺼진 외부 지식")));
        for(int i=0;i<2;i++) { server.enqueue(new ScriptedAiServer.Reply(503,"{\"error\":{\"code\":\"SERVICE_UNAVAILABLE\"}}",Map.of()));assertTrue(external.runOne()); }
        assertFalse(external.runOne(),"시도 횟수를 다 쓰면 다시 부르지 않는다");
        JsonNode job=json(call("GET","/api/recommendation-jobs/"+failed,null));
        assertEquals("RECOMMENDED",job.path("outcome").asString());assertTrue(job.path("recommendations").get(0).path("externalKnowledge").isNull());
        assertEquals("failed",jdbc.queryForObject("select status from recommendation_external_knowledge order by updated_at desc limit 1",String.class));
        String unsafe=analyze(a,w->List.of(result(w,List.of(a,b),"내부 주소 출처")));
        reply(found("내부 문서다.","http://10.0.0.8/wiki"));assertTrue(external.runOne());
        assertTrue(card(unsafe).path("externalKnowledge").isNull(),"안전하지 않은 URL은 버린다");
    }
    @Test void onlyCardsShownToTheUserAreQueued() throws Exception {
        UUID a=note("a",null,"요청 노트 문장이다."),b=note("b",null,"다른 노트 문장이다."),c=note("c",null,"세 번째 노트 문장이다.");
        analyze(a,w->{
            var results=new java.util.ArrayList<JsonNode>();
            for(int i=0;i<7;i++) results.add(result(w,List.of(a,b),"결론"+i));
            results.add(result(w,List.of(b,c),"요청 노트가 없는 결론"));
            return results;
        });
        assertEquals(5,jdbc.queryForObject("select count(*) from recommendation_external_knowledge where status='pending'",Integer.class));
    }
    private String analyze(UUID note,Function<Work,List<JsonNode>> results) throws Exception {
        action.set(w->new PipelineOutput(List.of(),List.of(),results.apply(w)));
        ready();String job=json(call("POST","/api/notes/"+note+"/recommendation-jobs",null).andExpect(status().isAccepted())).path("id").asString();
        ready();assertTrue(worker.runOne());return job;
    }
    private JsonNode card(String job) throws Exception {
        JsonNode body=json(call("GET","/api/recommendation-jobs/"+job,null).andExpect(status().isOk()));
        assertEquals("RECOMMENDED",body.path("outcome").asString());return body.path("recommendations").get(0);
    }
    private ScriptedAiServer.RecordedRequest lastExternalRequest() throws InterruptedException {
        while(true) { var request=server.takeRequest(java.time.Duration.ofSeconds(5));if(request.path().endsWith("/external-knowledge")) return request; }
    }
    /** 응답의 context는 요청과 같아야 하므로 대기 중인 카드의 작업 정보로 만든다. */
    private void reply(Function<ObjectNode,String> body) {
        var row=jdbc.queryForMap("""
            select k.result_id,k.attempts,j.id job,j.user_id,j.note_id,j.note_version,coalesce(j.input_revision,0) input_revision,coalesce(j.graph_revision,0) graph_revision
            from recommendation_external_knowledge k join analysis_result r on r.id=k.result_id join analysis_job j on j.id=r.job_id
            where k.status='pending' order by k.created_at,k.result_id limit 1""");
        ObjectNode context=mapper.createObjectNode();
        context.put("analysisJobId",row.get("job").toString());
        context.put("stageExecutionId",UUID.nameUUIDFromBytes((row.get("result_id")+":external-knowledge:"+(((Number)row.get("attempts")).intValue()+1)).getBytes(StandardCharsets.UTF_8)).toString());
        context.put("userId",row.get("user_id").toString());context.put("noteId",row.get("note_id").toString());
        context.put("noteVersion",((Number)row.get("note_version")).intValue());context.put("inputRevision",((Number)row.get("input_revision")).longValue());
        context.put("graphRevision",((Number)row.get("graph_revision")).longValue());context.put("pipelineVersion","ai-integration-2026-10-30-adopted.1");
        server.enqueue(new ScriptedAiServer.Reply(200,body.apply(context),Map.of("Content-Type","application/json")));
    }
    private Function<ObjectNode,String> found(String summary,String url) { return body("found",summary,url); }
    private Function<ObjectNode,String> body(String status,String summary,String url) {
        return context->{
            ObjectNode response=mapper.createObjectNode();response.set("context",context);
            response.putObject("meta").put("profileVersion","external-knowledge-llm-1").put("provider","fixture").put("model","external-model").put("modelRevision","fixture-revision-1")
                    .put("promptVersion","external-knowledge-llm-1").put("schemaVersion","1.0").put("preprocessingVersion","markdown-source-map-1").put("durationMs",4000);
            response.put("status",status);var items=response.putArray("externalKnowledge");
            if(summary!=null) items.addObject().put("summary",summary).put("sourceUrl",url).put("title","출처").put("retrievedAt","2026-11-18T00:00:00Z").put("support","yes");
            response.putObject("usage").put("searches",1).put("inputTokens",4000).put("outputTokens",500);
            return response.toString();
        };
    }
    private void ready() { server.enqueue(new ScriptedAiServer.Reply(200,"{\"status\":\"ready\",\"schemaVersion\":\"1.0\",\"profileVersions\":[\"fixture\"]}",Map.of())); }
    private ObjectNode result(Work job,List<UUID> notes,String conclusion) {
        ObjectNode r=mapper.createObjectNode();UUID id=UUID.randomUUID();r.put("id",id.toString());r.put("ref",id.toString());r.put("identitySha256",AiResponseValidator.hash(id.toString()));r.put("kind","graph");r.put("conclusion",conclusion);r.putArray("conditions");r.putArray("trace");
        ObjectNode detail=r.putObject("detail");detail.put("hypothesis",false);var concepts=detail.putObject("path").putArray("concepts");
        for(String label:List.of("재시도","멱등성",conclusion)) concepts.addObject().put("conceptId",UUID.randomUUID().toString()).put("label",label);
        r.put("derivationStatus","conclusion");r.put("soundness","sound");r.putArray("dependsOn");
        var novelty=r.putArray("noveltyNoteIds");var evidence=r.putArray("evidence");
        for(UUID note:notes) {
            JsonNode doc=job.documents().stream().filter(d->d.path("noteId").asString().equals(note.toString())).findFirst().orElseThrow();
            novelty.add(note.toString());var e=evidence.addObject();e.set("snapshotId",doc.path("snapshotId"));e.put("claimRef","c"+notes.indexOf(note));e.put("startOffset",0);e.put("endOffset",doc.path("body").asString().length());e.put("quote",doc.path("body").asString());
        }
        return r;
    }
}
