package com.example.capstone.concept;

import java.util.ArrayList;
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

/** v0.4 페이지 그래프: 노드는 페이지, 엣지는 키워드를 공유하는 페이지 쌍 하나. */
@SpringBootTest(properties={"capstone.ai.enabled=true","capstone.ai.pipeline.synthetic-stub=true","capstone.ai.execution.worker-enabled=false","capstone.ai.execution.poll-ms=3600000"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
class PageGraphIntegrationTest extends ApiIntegrationSupport {
    static final java.nio.file.Path policy=policy();
    static java.nio.file.Path policy() { try { var p=java.nio.file.Files.createTempFile("page-graph-policy-",".json");java.nio.file.Files.writeString(p,com.example.capstone.analysis.support.SyntheticPipelineServer.POLICY);return p; } catch(Exception e) { throw new IllegalStateException(e); } }
    @org.springframework.test.context.DynamicPropertySource static void config(org.springframework.test.context.DynamicPropertyRegistry p) { p.add("capstone.ai.pipeline.policy-file",policy::toString); }
    @org.junit.jupiter.api.AfterAll static void cleanupPolicy() throws Exception { java.nio.file.Files.delete(policy); }
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    @Autowired AnalysisWorkService work;

    @Test void pagesSharingKeywordsGetOneUndirectedEdgeAndIsolatedPagesStayAsNodes() throws Exception {
        UUID topic=topic("네트워크"),a=note("재시도",topic,"가"),b=note("멱등성",topic,"나"),c=note("인덱스",null,"다");
        analyze(a,Map.of(a,List.of("재시도","HTTP 메서드"),b,List.of("HTTP 메서드"," 재시도 "),c,List.of("B-tree")));
        JsonNode graph=json(call("GET","/api/graph",null).andExpect(status().isOk()));
        assertEquals("all",graph.path("scope").asString());assertEquals(3,graph.path("nodes").size());
        JsonNode first=graph.path("nodes").get(0);assertEquals(a.toString(),first.path("id").asString());assertEquals("재시도",first.path("title").asString());assertEquals(topic.toString(),first.path("topicId").asString());
        assertTrue(graph.path("nodes").get(2).path("topicId").isNull());
        assertEquals(1,graph.path("edges").size());JsonNode edge=graph.path("edges").get(0);
        assertEquals(java.util.Set.of(a.toString(),b.toString()),java.util.Set.of(edge.path("sourceId").asString(),edge.path("targetId").asString()));
        assertEquals(List.of("HTTP 메서드","재시도"),mapper.treeToValue(edge.path("sharedKeywords"),List.class));
        assertFalse(edge.has("type"));assertFalse(edge.has("status"));assertFalse(edge.has("origin"));
    }
    @Test void generatedKnowledgeAndOtherUsersDoNotCreateEdges() throws Exception {
        UUID a=note("a",null,"가"),b=note("b",null,"나");
        analyze(a,Map.of(a,List.of("캐시"),b,List.of("락")),List.of(a,b));
        UUID foreign=foreignNoteWithKeyword("캐시");
        JsonNode graph=json(call("GET","/api/graph",null));
        assertEquals(2,graph.path("nodes").size());assertTrue(graph.path("edges").isEmpty());
        assertFalse(graph.toString().contains(foreign.toString()));
        call("GET","/api/graph",null,"invalid").andExpect(status().isUnauthorized());
    }
    @Test void topicScopeKeepsOnlyThatTopicAndValidatesParameters() throws Exception {
        UUID topic=topic("주제"),inside=note("안",topic,"가"),also=note("또",topic,"나"),outside=note("밖",null,"다");
        analyze(inside,Map.of(inside,List.of("공유"),also,List.of("공유"),outside,List.of("공유")));
        JsonNode scoped=json(call("GET","/api/graph?scope=topic&topicId="+topic,null).andExpect(status().isOk()));
        assertEquals("topic",scoped.path("scope").asString());assertEquals(2,scoped.path("nodes").size());assertEquals(1,scoped.path("edges").size());
        assertFalse(scoped.toString().contains(outside.toString()));
        JsonNode unassigned=json(call("GET","/api/graph?scope=topic&topicId=none",null).andExpect(status().isOk()));
        assertEquals(1,unassigned.path("nodes").size());assertTrue(unassigned.path("edges").isEmpty());
        assertEquals(3,json(call("GET","/api/graph?scope=all",null)).path("edges").size());
        call("GET","/api/graph?scope=topic",null).andExpect(status().isBadRequest());
        call("GET","/api/graph?scope=note",null).andExpect(status().isBadRequest());
        call("GET","/api/graph?scope=topic&topicId=not-a-uuid",null).andExpect(status().isBadRequest());
        call("GET","/api/graph?scope=topic&topicId="+UUID.randomUUID(),null).andExpect(status().isNotFound());
        call("GET","/api/graph?scope=topic&topicId="+topic,null,otherBearer).andExpect(status().isNotFound());
    }
    private void analyze(UUID requested,Map<UUID,List<String>> keywords) { analyze(requested,keywords,List.of()); }
    private void analyze(UUID requested,Map<UUID,List<String>> keywords,List<UUID> generatedBetween) {
        work.submit(userId,requested);Work job=work.claim();var items=new ArrayList<JsonNode>();
        for(JsonNode doc:job.documents()) {
            int index=0;
            for(String label:keywords.getOrDefault(UUID.fromString(doc.path("noteId").asString()),List.of())) {
                ObjectNode item=mapper.createObjectNode();item.put("id",UUID.randomUUID().toString());item.set("snapshotId",doc.path("snapshotId"));item.put("kind","concept");item.put("ref","c"+index++);
                item.putObject("value").put("id",UUID.randomUUID().toString()).put("label",label).putArray("mentions");items.add(item);
            }
        }
        var results=new ArrayList<JsonNode>();
        if(!generatedBetween.isEmpty()) {
            ObjectNode r=mapper.createObjectNode();UUID id=UUID.randomUUID();r.put("id",id.toString());r.put("ref","generated");r.put("identitySha256",AiResponseValidator.hash(id.toString()));r.put("kind","derivation");r.put("conclusion","생성된 지식");
            r.putArray("conditions");r.putArray("trace");r.putObject("detail");r.put("derivationStatus","conclusion");r.put("soundness","sound");r.putArray("dependsOn");var novelty=r.putArray("noveltyNoteIds");var evidence=r.putArray("evidence");
            for(JsonNode doc:job.documents()) if(generatedBetween.contains(UUID.fromString(doc.path("noteId").asString()))) {
                novelty.add(doc.path("noteId").asString());evidence.addObject().put("snapshotId",doc.path("snapshotId").asString()).put("claimRef","premise").put("startOffset",0).put("endOffset",1).put("quote",doc.path("body").asString());
            }
            results.add(r);
        }
        work.complete(job,new PipelineOutput(List.of(),items,results));
    }
    private UUID foreignNoteWithKeyword(String label) throws Exception {
        UUID one=UUID.fromString(json(call("POST","/api/notes","{\"title\":\"남의 노트\",\"topicId\":null,\"body\":\"라\"}",otherBearer)).path("id").asString());
        UUID two=UUID.fromString(json(call("POST","/api/notes","{\"title\":\"남의 다른 노트\",\"topicId\":null,\"body\":\"마\"}",otherBearer)).path("id").asString());
        work.submit(otherId,one);Work job=work.claim();var items=new ArrayList<JsonNode>();
        for(JsonNode doc:job.documents()) {
            ObjectNode item=mapper.createObjectNode();item.put("id",UUID.randomUUID().toString());item.set("snapshotId",doc.path("snapshotId"));item.put("kind","concept");item.put("ref","c");
            item.putObject("value").put("id",UUID.randomUUID().toString()).put("label",label).putArray("mentions");items.add(item);
        }
        work.complete(job,new PipelineOutput(List.of(),items,List.of()));
        assertEquals(1,json(call("GET","/api/graph",null,otherBearer)).path("edges").size());
        return two;
    }
}
