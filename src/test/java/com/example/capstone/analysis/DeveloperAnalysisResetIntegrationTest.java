package com.example.capstone.analysis;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
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
import com.example.capstone.analysis.support.ScriptedAiServer;
import com.example.capstone.analysis.worker.AnalysisPipeline;
import com.example.capstone.analysis.worker.AnalysisPipeline.PipelineOutput;
import com.example.capstone.analysis.worker.AnalysisPipeline.Work;
import com.example.capstone.analysis.worker.AnalysisWorker;
import com.example.capstone.support.ApiIntegrationSupport;
import com.example.capstone.user.domain.AppUser;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 개발자용 분석 결과 초기화(#158): 답한 추천이 초기화 뒤 다시 나오고, 다른 계정의 데이터는 그대로다. */
@SpringBootTest(properties={"capstone.developer.emails=dev@example.com","capstone.ai.execution.readiness-cache=0s","capstone.ai.enabled=true","capstone.ai.execution.budget-microunits=1000","capstone.ai.execution.currency=KRW","capstone.ai.execution.required-profiles=fixture","capstone.ai.execution.worker-enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
@Import(DeveloperAnalysisResetIntegrationTest.PipelineConfig.class)
class DeveloperAnalysisResetIntegrationTest extends ApiIntegrationSupport {
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    static final ScriptedAiServer server=server();
    static ScriptedAiServer server() { try { return new ScriptedAiServer("synthetic-token-only",2097152); } catch(Exception e) { throw new IllegalStateException(); } }
    static final AtomicReference<Function<Work,PipelineOutput>> action=new AtomicReference<>();
    @TestConfiguration static class PipelineConfig { @Bean @Primary AnalysisPipeline pipeline() { return work->action.get().apply(work); } }
    @DynamicPropertySource static void ai(DynamicPropertyRegistry p) {
        p.add("capstone.ai.client.base-url",()->server.baseUri().toString());p.add("capstone.ai.client.service-token",()->"synthetic-token-only");
    }
    @Autowired AnalysisWorker worker;

    @Test void resetBringsAnsweredRecommendationBackAndLeavesOtherAccountsAlone() throws Exception {
        String developer=tokens.issue(users.saveAndFlush(new AppUser("Dev@Example.com","개발자",Instant.now())).getId());
        UUID a=noteAs(bearer,"재시도 정책","클라이언트는 응답 시간이 초과되면 요청을 재전송한다."),b=noteAs(bearer,"멱등성","멱등한 연산은 여러 번 실행해도 결과가 같다.");
        UUID oa=noteAs(otherBearer,"인덱스","인덱스는 조회를 빠르게 한다."),ob=noteAs(otherBearer,"정렬","정렬된 열은 범위 조회에 유리하다.");
        action.set(w->new PipelineOutput(List.of(),List.of(),List.of(result(w,List.of(a,b)))));
        answer(a,bearer,"understood");
        assertTrue(analyze(a,bearer).isEmpty(),"답한 추천은 다시 분석해도 나오지 않는다");
        action.set(w->new PipelineOutput(List.of(),List.of(),List.of(result(w,List.of(oa,ob)))));
        answer(oa,otherBearer,"understood");
        Map<String,Integer> otherBefore=rows(otherId);

        call("POST","/api/developer/users/"+userId+"/analysis-reset",null).andExpect(status().isForbidden());
        call("POST","/api/developer/users/not-a-uuid/analysis-reset",null).andExpect(status().isForbidden());
        call("POST","/api/developer/users/"+UUID.randomUUID()+"/analysis-reset",null,developer).andExpect(status().isNotFound());
        call("POST","/api/developer/users/not-a-uuid/analysis-reset",null,developer).andExpect(status().isBadRequest());
        assertEquals(2,rows(userId).get("analysis_job"));

        JsonNode done=json(call("POST","/api/developer/users/"+userId+"/analysis-reset",null,developer).andExpect(status().isOk()));
        assertEquals(userId.toString(),done.path("userId").asString());
        assertEquals(2,done.path("jobs").asInt());assertEquals(2,done.path("results").asInt());assertEquals(1,done.path("feedback").asInt());
        rows(userId).forEach((table,count)->assertEquals(0,count,table));
        assertEquals(otherBefore,rows(otherId));
        assertEquals(2,jdbc.queryForObject("select count(*) from note where user_id=?",Integer.class,userId));
        assertThrows(Exception.class,()->jdbc.update("delete from analysis_review_event where user_id=?",otherId),"초기화 밖의 삭제는 계속 막힌다");

        action.set(w->new PipelineOutput(List.of(),List.of(),List.of(result(w,List.of(a,b)))));
        assertEquals(1,analyze(a,bearer).size(),"초기화 뒤 같은 노트를 분석하면 추천이 다시 나온다");
        JsonNode again=json(call("POST","/api/developer/users/"+userId+"/analysis-reset",null,developer).andExpect(status().isOk()));
        assertEquals(1,again.path("jobs").asInt());assertEquals(0,again.path("feedback").asInt());
        call("DELETE","/api/notes/"+a,null).andExpect(status().isNoContent());
    }

    private void answer(UUID note,String token,String action) throws Exception {
        String card=analyze(note,token).get(0).path("id").asString();
        call("POST","/api/recommendations/"+card+"/feedback","{\"action\":\""+action+"\"}",token).andExpect(status().isNoContent());
    }
    private JsonNode analyze(UUID note,String token) throws Exception {
        ready();String job=json(call("POST","/api/notes/"+note+"/recommendation-jobs",null,token).andExpect(status().isAccepted())).path("id").asString();
        ready();assertTrue(worker.runOne());
        return json(call("GET","/api/recommendation-jobs/"+job,null,token).andExpect(status().isOk())).path("recommendations");
    }
    private Map<String,Integer> rows(UUID user) {
        var counts=new java.util.LinkedHashMap<String,Integer>();
        for(String table:List.of("analysis_job","analysis_snapshot","analysis_stage","analysis_fact","analysis_result","analysis_result_evidence","analysis_dependency","analysis_review_event","analysis_graph_item","recommendation_feedback","recommendation_external_knowledge"))
            counts.put(table,jdbc.queryForObject("select count(*) from "+table+" where user_id=?",Integer.class,user));
        return counts;
    }
    private UUID noteAs(String token,String title,String body) throws Exception {
        var fields=new java.util.LinkedHashMap<String,Object>();fields.put("title",title);fields.put("topicId",null);fields.put("body",body);
        return UUID.fromString(json(call("POST","/api/notes",mapper.writeValueAsString(fields),token).andExpect(status().isCreated())).path("id").asString());
    }
    private void ready() { server.enqueue(new ScriptedAiServer.Reply(200,"{\"status\":\"ready\",\"schemaVersion\":\"1.0\",\"profileVersions\":[\"fixture\"]}",Map.of())); }
    private ObjectNode result(Work job,List<UUID> notes) {
        ObjectNode r=mapper.createObjectNode();UUID id=UUID.randomUUID();r.put("id",id.toString());r.put("ref",id.toString());r.put("identitySha256",AiResponseValidator.hash(id.toString()));r.put("kind","graph");r.put("conclusion","결론");r.putArray("conditions");r.putArray("trace");
        ObjectNode detail=r.putObject("detail");detail.put("hypothesis",false);var concepts=detail.putObject("path").putArray("concepts");
        for(String label:List.of("재시도","멱등성")) concepts.addObject().put("conceptId",UUID.randomUUID().toString()).put("label",label);
        r.put("derivationStatus","conclusion");r.put("soundness","sound");r.putArray("dependsOn");
        var novelty=r.putArray("noveltyNoteIds");var evidence=r.putArray("evidence");
        for(UUID note:notes) {
            JsonNode doc=job.documents().stream().filter(d->d.path("noteId").asString().equals(note.toString())).findFirst().orElseThrow();
            novelty.add(note.toString());var e=evidence.addObject();e.set("snapshotId",doc.path("snapshotId"));e.put("claimRef","c"+notes.indexOf(note));e.put("startOffset",0);e.put("endOffset",doc.path("body").asString().length());e.put("quote",doc.path("body").asString());
        }
        return r;
    }
}
