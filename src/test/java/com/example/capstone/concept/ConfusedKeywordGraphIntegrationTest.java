package com.example.capstone.concept;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

/** "이해 안 됨"(#156): 가장 최근 답이 confused인 추천의 경로 키워드를 주제 그래프와 검색에 표시한다. */
@SpringBootTest(properties={"capstone.ai.enabled=true","capstone.ai.pipeline.synthetic-stub=true","capstone.ai.execution.worker-enabled=false","capstone.ai.execution.poll-ms=3600000"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
class ConfusedKeywordGraphIntegrationTest extends ApiIntegrationSupport {
    static final java.nio.file.Path policy=policy();
    static java.nio.file.Path policy() { try { var p=java.nio.file.Files.createTempFile("confused-graph-policy-",".json");java.nio.file.Files.writeString(p,com.example.capstone.analysis.support.SyntheticPipelineServer.POLICY);return p; } catch(Exception e) { throw new IllegalStateException(e); } }
    @org.springframework.test.context.DynamicPropertySource static void config(org.springframework.test.context.DynamicPropertyRegistry p) { p.add("capstone.ai.pipeline.policy-file",policy::toString); }
    @org.junit.jupiter.api.AfterAll static void cleanupPolicy() throws Exception { java.nio.file.Files.delete(policy); }
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    @Autowired AnalysisWorkService work;

    UUID network,retry,idem;

    /** 네트워크 주제에 노트 둘(재시도·멱등성), 미분류 노트 하나. 추천 경로는 paths 순서대로 결론0, 결론1 ... 이다. */
    Map<String,String> seed(List<List<String>> paths) throws Exception {
        network=topic("네트워크");
        retry=note("재시도",network,"클라이언트는 요청을 재전송한다.");idem=note("멱등성",network,"멱등한 연산은 결과가 같다.");
        UUID memo=note("메모",null,"메모 문장이다.");
        var keywords=new LinkedHashMap<UUID,List<String>>();
        keywords.put(retry,List.of("재시도","타임아웃"));keywords.put(idem,List.of("멱등성","PUT"));keywords.put(memo,List.of("메모 키워드"));
        return analyze(userId,retry,keywords,paths,List.of(retry,idem));
    }

    @Test void confusedAnswerMarksPathKeywordsInGraphAndSearch() throws Exception {
        // 재시도는 같은 개념 id로, 멱등성은 id 없이 표기(공백·대소문자 차이)로 그래프의 키워드를 찾는다.
        String id=seed(List.of(List.of("재시도","~ 멱등성 "))).get("결론0");
        assertFalse(keyword(network,"재시도").path("confused").asBoolean());
        assertFalse(json(call("GET","/api/graph/topics",null)).path("topics").get(0).path("hasConfused").asBoolean());
        feedback(id,"confused");

        JsonNode top=json(call("GET","/api/graph/topics",null).andExpect(status().isOk()));
        assertTrue(top.path("topics").get(0).path("hasConfused").asBoolean());assertFalse(top.path("unassigned").path("hasConfused").asBoolean());
        assertTrue(keyword(network,"재시도").path("confused").asBoolean());assertTrue(keyword(network,"멱등성").path("confused").asBoolean());
        assertFalse(keyword(network,"타임아웃").path("confused").asBoolean());assertFalse(keyword(network,"PUT").path("confused").asBoolean());

        JsonNode found=json(call("GET","/api/graph/search?q=재시도",null).andExpect(status().isOk())).path("results");
        assertEquals("keyword",found.get(0).path("kind").asString());assertTrue(found.get(0).path("confused").asBoolean());
        JsonNode topic=json(call("GET","/api/graph/search?q=네트",null)).path("results").get(0);
        assertEquals("topic",topic.path("kind").asString());assertFalse(topic.path("confused").asBoolean());
        assertFalse(json(call("GET","/api/graph/search?q=타임",null)).path("results").get(0).path("confused").asBoolean());
    }

    @Test void laterUnderstoodOrKnownClearsTheMark() throws Exception {
        Map<String,String> ids=seed(List.of(List.of("재시도"),List.of("멱등성")));
        feedback(ids.get("결론0"),"confused");feedback(ids.get("결론1"),"confused");
        assertTrue(keyword(network,"재시도").path("confused").asBoolean());assertTrue(keyword(network,"멱등성").path("confused").asBoolean());
        feedback(ids.get("결론0"),"understood");feedback(ids.get("결론1"),"known");
        assertFalse(keyword(network,"재시도").path("confused").asBoolean());assertFalse(keyword(network,"멱등성").path("confused").asBoolean());
        assertFalse(json(call("GET","/api/graph/topics",null)).path("topics").get(0).path("hasConfused").asBoolean());
        assertFalse(json(call("GET","/api/graph/search?q=멱등",null)).path("results").get(0).path("confused").asBoolean());
    }

    @Test void evidenceLostRecommendationIsNotMarked() throws Exception {
        String id=seed(List.of(List.of("멱등성"))).get("결론0");
        feedback(id,"confused");
        assertTrue(keyword(network,"멱등성").path("confused").asBoolean());
        call("PUT","/api/notes/"+retry,"{\"title\":\"재시도\",\"body\":\"근거 문장을 모두 지운 본문이다.\",\"version\":0}").andExpect(status().isOk());
        assertEquals("evidence_lost",jdbc.queryForObject("select freshness from analysis_result where recommendation_id=?",String.class,UUID.fromString(id)));
        assertFalse(keyword(network,"멱등성").path("confused").asBoolean());
        assertFalse(json(call("GET","/api/graph/topics",null)).path("topics").get(0).path("hasConfused").asBoolean());
        assertFalse(json(call("GET","/api/graph/search?q=멱등",null)).path("results").get(0).path("confused").asBoolean());
    }

    @Test void otherUsersAnswersDoNotAffectTheGraph() throws Exception {
        seed(List.of(List.of("재시도")));
        UUID a=otherNote("재시도","클라이언트는 요청을 재전송한다."),b=otherNote("멱등성","멱등한 연산은 결과가 같다.");
        var keywords=new LinkedHashMap<UUID,List<String>>();keywords.put(a,List.of("재시도"));keywords.put(b,List.of("멱등성"));
        String foreign=analyze(otherId,a,keywords,List.of(List.of("재시도","멱등성")),List.of(a,b)).get("결론0");
        call("POST","/api/recommendations/"+foreign+"/feedback","{\"action\":\"confused\"}",otherBearer).andExpect(status().isNoContent());

        assertTrue(json(call("GET","/api/graph/search?q=재시도",null,otherBearer)).path("results").get(0).path("confused").asBoolean());
        assertFalse(keyword(network,"재시도").path("confused").asBoolean());
        assertFalse(json(call("GET","/api/graph/topics",null)).path("topics").get(0).path("hasConfused").asBoolean());
        JsonNode mine=json(call("GET","/api/graph/search?q=재시도",null)).path("results");
        assertEquals("keyword",mine.get(0).path("kind").asString());assertFalse(mine.get(0).path("confused").asBoolean());
    }

    JsonNode keyword(UUID topic,String label) throws Exception {
        for(JsonNode keyword:json(call("GET","/api/graph/topics/"+topic,null).andExpect(status().isOk())).path("keywords"))
            if(keyword.path("label").asString().equals(label)) return keyword;
        throw new AssertionError("키워드 없음: "+label);
    }
    void feedback(String id,String action) throws Exception {
        call("POST","/api/recommendations/"+id+"/feedback","{\"action\":\""+action+"\"}").andExpect(status().isNoContent());
    }
    UUID otherNote(String title,String body) throws Exception {
        var fields=new LinkedHashMap<String,Object>();fields.put("title",title);fields.put("topicId",null);fields.put("body",body);
        return UUID.fromString(json(call("POST","/api/notes",mapper.writeValueAsString(fields),otherBearer).andExpect(status().isCreated())).path("id").asString());
    }

    /**
     * 노트별 개념과 추천 결과를 한 작업으로 저장하고 결론별 추천 id를 돌려준다.
     * 경로 라벨 앞 "~"는 개념 id 없이(임의 id) 표기만으로 키워드를 찾게 한다. 추천의 근거는 evidenceNotes의 본문 전체다.
     */
    Map<String,String> analyze(UUID user,UUID requested,Map<UUID,List<String>> keywords,List<List<String>> paths,List<UUID> evidenceNotes) {
        work.submit(user,requested);Work job=work.claim();var items=new ArrayList<JsonNode>();var conceptIds=new HashMap<String,String>();
        for(JsonNode doc:job.documents()) {
            int index=0;
            for(String label:keywords.getOrDefault(UUID.fromString(doc.path("noteId").asString()),List.of())) {
                String id=UUID.randomUUID().toString();
                ObjectNode item=mapper.createObjectNode();item.put("id",UUID.randomUUID().toString());item.set("snapshotId",doc.path("snapshotId"));item.put("kind","concept");item.put("ref","c"+index++);
                item.putObject("value").put("id",id).put("label",label).putArray("mentions");
                items.add(item);conceptIds.put(label,id);
            }
        }
        var results=new ArrayList<JsonNode>();
        for(int i=0;i<paths.size();i++) {
            ObjectNode r=mapper.createObjectNode();UUID id=UUID.randomUUID();r.put("id",id.toString());r.put("ref",id.toString());r.put("identitySha256",AiResponseValidator.hash(id.toString()));
            r.put("kind","graph");r.put("conclusion","결론"+i);r.putArray("conditions");r.putArray("trace");
            ObjectNode detail=r.putObject("detail");detail.put("hypothesis",false);var concepts=detail.putObject("path").putArray("concepts");
            for(String raw:paths.get(i)) {
                boolean byLabel=raw.startsWith("~");String label=byLabel?raw.substring(1):raw;
                concepts.addObject().put("conceptId",byLabel?UUID.randomUUID().toString():conceptIds.get(label)).put("label",label);
            }
            r.put("derivationStatus","conclusion");r.put("soundness","sound");r.putArray("dependsOn");
            var novelty=r.putArray("noveltyNoteIds");var evidence=r.putArray("evidence");
            for(UUID note:evidenceNotes) {
                JsonNode doc=job.documents().stream().filter(d -> d.path("noteId").asString().equals(note.toString())).findFirst().orElseThrow();
                novelty.add(note.toString());var e=evidence.addObject();e.set("snapshotId",doc.path("snapshotId"));e.put("claimRef","c"+evidenceNotes.indexOf(note));
                e.put("startOffset",0);e.put("endOffset",doc.path("body").asString().length());e.put("quote",doc.path("body").asString());
            }
            results.add(r);
        }
        work.complete(job,new PipelineOutput(List.of(),items,results));
        var ids=new LinkedHashMap<String,String>();
        for(int i=0;i<paths.size();i++)
            ids.put("결론"+i,jdbc.queryForObject("select recommendation_id from analysis_result where user_id=? and job_id=? and conclusion=?",UUID.class,user,job.id(),"결론"+i).toString());
        return ids;
    }
}
