package com.example.capstone.analysis;

import java.util.List;
import java.util.Map;
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

/** 결과의 current·stale·evidence_lost는 인용한 근거 문장과 같은 요청 노트의 새 분석으로만 바뀐다. */
@SpringBootTest(properties={"capstone.ai.enabled=true","capstone.ai.pipeline.synthetic-stub=true","capstone.ai.execution.worker-enabled=false","capstone.ai.execution.poll-ms=3600000"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
class ResultFreshnessIntegrationTest extends ApiIntegrationSupport {
    static final java.nio.file.Path policy=policy();
    static java.nio.file.Path policy() { try { var p=java.nio.file.Files.createTempFile("freshness-policy-",".json");java.nio.file.Files.writeString(p,com.example.capstone.analysis.support.SyntheticPipelineServer.POLICY);return p; } catch(Exception e) { throw new IllegalStateException(e); } }
    @org.springframework.test.context.DynamicPropertySource static void config(org.springframework.test.context.DynamicPropertyRegistry p) { p.add("capstone.ai.pipeline.policy-file",policy::toString); }
    @org.junit.jupiter.api.AfterAll static void cleanupPolicy() throws Exception { java.nio.file.Files.delete(policy); }
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    @Autowired AnalysisWorkService work;
    static final String BODY="재시도는 요청을 다시 보낸다. 멱등성은 결과를 같게 한다.";
    static final String FIRST="재시도는 요청을 다시 보낸다.",SECOND="멱등성은 결과를 같게 한다.";

    @Test void deletingCitedSentenceLosesOnlyThatResultAndRestoringDoesNotRecover() throws Exception {
        UUID a=note("a",null,BODY),b=note("b",null,"근거 문장이다.");work.submit(userId,a);Work job=work.claim();
        ObjectNode first=result(job,Map.of(a,FIRST,b,"근거 문장이다.")),second=result(job,Map.of(a,SECOND,b,"근거 문장이다."));
        work.complete(job,new PipelineOutput(List.of(),List.of(),List.of(first,second)));
        put(a,"a",SECOND+" 새 문장을 더했다.",0);
        assertEquals("evidence_lost",freshness(first));assertEquals("current",freshness(second));
        put(a,"a",BODY,1);
        assertEquals("evidence_lost",freshness(first));assertEquals("current",freshness(second));
        assertEquals(409,call("POST","/api/candidates/"+id(first)+"/review",accept("lost")).andReturn().getResponse().getStatus());
    }
    @Test void titleOtherSentenceAndOtherNoteChangesKeepCurrent() throws Exception {
        UUID a=note("a",null,BODY),b=note("b",null,"근거 문장이다."),c=note("c",null,"상관없는 노트");work.submit(userId,a);Work job=work.claim();
        ObjectNode kept=result(job,Map.of(a,FIRST,b,"근거 문장이다."));work.complete(job,new PipelineOutput(List.of(),List.of(),List.of(kept)));
        put(a,"새 제목",BODY,0);
        put(a,"새 제목",FIRST+" 다른 문장으로 바꿨다.",1);
        put(c,"c","바뀐 상관없는 노트",0);
        assertEquals("current",freshness(kept));
        assertEquals(1,json(call("GET","/api/candidates",null)).path("results").size());
    }
    @Test void newerAnalysisOfSameRequestedNoteStalesPreviousResultsOnly() throws Exception {
        UUID a=note("a",null,BODY),b=note("b",null,"근거 문장이다.");
        work.submit(userId,a);Work first=work.claim();ObjectNode old=result(first,Map.of(a,FIRST,b,"근거 문장이다."));work.complete(first,new PipelineOutput(List.of(),List.of(),List.of(old)));
        work.submit(userId,b);Work other=work.claim();ObjectNode fromOther=result(other,Map.of(b,"근거 문장이다.",a,SECOND));work.complete(other,new PipelineOutput(List.of(),List.of(),List.of(fromOther)));
        assertEquals("current",freshness(old));
        work.submit(userId,a);Work newer=work.claim();ObjectNode fresh=result(newer,Map.of(a,SECOND,b,"근거 문장이다."));
        work.complete(newer,new PipelineOutput(List.of(),List.of(),List.of(fresh)));
        assertEquals("stale",freshness(old));assertEquals("current",freshness(fresh));assertEquals("current",freshness(fromOther));
        assertEquals(409,call("POST","/api/candidates/"+id(old)+"/review",accept("stale")).andReturn().getResponse().getStatus());
        assertEquals(201,call("POST","/api/candidates/"+id(fresh)+"/review",accept("fresh")).andReturn().getResponse().getStatus());
    }
    @Test void dependentResultBecomesStaleWhenParentLosesEvidence() throws Exception {
        UUID a=note("a",null,BODY),b=note("b",null,"근거 문장이다."),c=note("c",null,"세 번째 근거.");work.submit(userId,a);Work job=work.claim();
        ObjectNode parent=result(job,Map.of(a,FIRST,b,"근거 문장이다.")),derived=result(job,Map.of(b,"근거 문장이다.",c,"세 번째 근거."));derived.putArray("dependsOn").add(parent.path("id").asString());
        work.complete(job,new PipelineOutput(List.of(),List.of(),List.of(parent,derived)));
        put(a,"a",SECOND,0);
        assertEquals("evidence_lost",freshness(parent));assertEquals("stale",freshness(derived));
    }
    private void put(UUID note,String title,String body,int version) throws Exception {
        call("PUT","/api/notes/"+note,mapper.writeValueAsString(Map.of("title",title,"body",body,"version",version))).andExpect(status().isOk());
    }
    private String accept(String key) { return "{\"expectedRevision\":1,\"action\":\"accept\",\"reason\":null,\"learning\":\"new_understanding\",\"editedConclusion\":null,\"idempotencyKey\":\""+key+"\"}"; }
    private UUID id(ObjectNode result) { return UUID.fromString(result.path("id").asString()); }
    private String freshness(ObjectNode result) { return jdbc.queryForObject("select freshness from analysis_result where id=?",String.class,id(result)); }
    private ObjectNode result(Work job,Map<UUID,String> quotes) {
        ObjectNode r=mapper.createObjectNode();UUID id=UUID.randomUUID();r.put("id",id.toString());r.put("ref",id.toString());r.put("identitySha256",AiResponseValidator.hash(id.toString()));r.put("kind","derivation");r.put("conclusion","합성결론");r.putArray("conditions");r.putArray("trace");r.putObject("detail");r.put("derivationStatus","conclusion");r.put("soundness","sound");r.putArray("dependsOn");
        var novelty=r.putArray("noveltyNoteIds");var evidence=r.putArray("evidence");
        for(JsonNode doc:job.documents()) {
            String quote=quotes.get(UUID.fromString(doc.path("noteId").asString()));if(quote==null) continue;
            String body=doc.path("body").asString();int start=body.indexOf(quote);
            novelty.add(doc.path("noteId").asString());var e=evidence.addObject();e.set("snapshotId",doc.path("snapshotId"));e.put("claimRef","premise");e.put("startOffset",start);e.put("endOffset",start+quote.length());e.put("quote",quote);
        }
        return r;
    }
}
