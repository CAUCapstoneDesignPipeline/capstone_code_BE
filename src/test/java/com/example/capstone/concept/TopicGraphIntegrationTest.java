package com.example.capstone.concept;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/** 주제 그래프(Galaxy, #143)와 그래프 검색(#144)이 함께 쓰는 키워드 배치 규칙을 실제 분석 저장 경로로 검증한다. */
@SpringBootTest(properties={"capstone.ai.enabled=true","capstone.ai.pipeline.synthetic-stub=true","capstone.ai.execution.worker-enabled=false","capstone.ai.execution.poll-ms=3600000"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
class TopicGraphIntegrationTest extends ApiIntegrationSupport {
    static final java.nio.file.Path policy=policy();
    static java.nio.file.Path policy() { try { var p=java.nio.file.Files.createTempFile("topic-graph-policy-",".json");java.nio.file.Files.writeString(p,com.example.capstone.analysis.support.SyntheticPipelineServer.POLICY);return p; } catch(Exception e) { throw new IllegalStateException(e); } }
    @org.springframework.test.context.DynamicPropertySource static void config(org.springframework.test.context.DynamicPropertyRegistry p) { p.add("capstone.ai.pipeline.policy-file",policy::toString); }
    @org.junit.jupiter.api.AfterAll static void cleanupPolicy() throws Exception { java.nio.file.Files.delete(policy); }
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    @Autowired AnalysisWorkService work;

    UUID network,transport,tcp,os,udpNote,tcpNote;

    /**
     * 네트워크 ─ 전송 계층 ─ TCP (3단계), 운영체제(최상위), 미분류 노트 하나.
     * 재전송은 전송 계층 노트 1개, TCP 노트 2개에 나오므로 TCP에 속한다.
     */
    void seed() throws Exception {
        network=topic("네트워크");transport=child("전송 계층",network);tcp=child("TCP",transport);os=topic("운영체제");
        UUID http=note("HTTP",network,"가");udpNote=note("UDP",transport,"나");tcpNote=note("혼잡 제어",tcp,"다");
        UUID flow=note("흐름 제어",tcp,"라"),sched=note("스케줄러",os,"마"),memo=note("메모",null,"바");
        var keywords=new LinkedHashMap<UUID,List<String>>();
        keywords.put(http,List.of("HTTP","멱등성","+이해 지식"));keywords.put(udpNote,List.of("UDP","재전송"));keywords.put(tcpNote,List.of("혼잡 제어","재전송"));
        keywords.put(flow,List.of(" 재전송 ","흐름 제어"));keywords.put(sched,List.of("스케줄링"));keywords.put(memo,List.of("메모 키워드"));
        analyze(http,keywords,List.of(List.of("HTTP","재전송"),List.of("멱등성","스케줄링"),List.of("UDP","혼잡 제어")));
    }
    UUID child(String name,UUID parent) throws Exception {
        return UUID.fromString(json(call("POST","/api/topics",mapper.writeValueAsString(Map.of("name",name,"parentId",parent))).andExpect(status().isCreated())).path("id").asString());
    }
    static List<String> values(JsonNode array,String field) { var out=new ArrayList<String>();array.forEach(node -> out.add(node.path(field).asString()));return out; }
    static Set<Set<String>> edges(JsonNode graph) { var out=new java.util.HashSet<Set<String>>();graph.path("edges").forEach(e -> out.add(Set.of(e.path("sourceId").asString(),e.path("targetId").asString())));return out; }

    @Test void topLevelShowsOnlyTopTopicsWithSubtreeCountsAndMergedEdges() throws Exception {
        seed();
        JsonNode top=json(call("GET","/api/graph/topics",null).andExpect(status().isOk()));
        assertEquals(List.of(network.toString(),os.toString()),values(top.path("topics"),"id"));
        JsonNode net=top.path("topics").get(0);
        assertEquals(7,net.path("keywordCount").asInt());assertTrue(net.path("hasChildren").asBoolean());assertEquals(1,net.path("childTopicCount").asInt());assertTrue(net.path("hasRecommended").asBoolean());
        assertFalse(top.path("topics").get(1).path("hasRecommended").asBoolean());
        assertTrue(top.path("keywords").isEmpty());
        assertEquals(Set.of(Set.of(network.toString(),os.toString())),edges(top));
        assertEquals(1,top.path("unassigned").path("keywordCount").asInt());assertFalse(top.path("unassigned").path("hasRecommended").asBoolean());
    }

    @Test void eachLevelHasChildTopicsAndOnlyDirectKeywords() throws Exception {
        seed();
        JsonNode net=json(call("GET","/api/graph/topics/"+network,null).andExpect(status().isOk()));
        assertEquals(List.of(transport.toString()),values(net.path("topics"),"id"));assertEquals(4,net.path("topics").get(0).path("keywordCount").asInt());
        assertEquals(List.of("HTTP","멱등성","이해 지식"),values(net.path("keywords"),"label"));
        assertEquals(List.of("extracted","extracted","generated"),values(net.path("keywords"),"origin"));
        assertTrue(net.path("unassigned").isNull());
        String httpId=net.path("keywords").get(0).path("id").asString();
        assertEquals(Set.of(Set.of(httpId,transport.toString())),edges(net));

        JsonNode mid=json(call("GET","/api/graph/topics/"+transport,null));
        assertEquals(List.of(tcp.toString()),values(mid.path("topics"),"id"));assertEquals(List.of("UDP"),values(mid.path("keywords"),"label"));
        assertEquals(Set.of(Set.of(mid.path("keywords").get(0).path("id").asString(),tcp.toString())),edges(mid));

        JsonNode leaf=json(call("GET","/api/graph/topics/"+tcp,null));
        assertTrue(leaf.path("topics").isEmpty());
        assertEquals(List.of("재전송","혼잡 제어","흐름 제어"),values(leaf.path("keywords"),"label"));
        JsonNode resend=leaf.path("keywords").get(0);
        assertEquals(tcp.toString(),resend.path("topicId").asString());assertEquals(3,resend.path("conceptCount").asInt());
        assertEquals(List.of("UDP","혼잡 제어","흐름 제어"),values(resend.path("documents"),"title"));
        assertTrue(leaf.path("edges").isEmpty());

        JsonNode again=json(call("GET","/api/graph/topics/"+tcp,null));
        assertEquals(resend.path("id").asString(),again.path("keywords").get(0).path("id").asString());
    }

    @Test void unassignedLevelAndOwnershipAndValidation() throws Exception {
        seed();
        JsonNode none=json(call("GET","/api/graph/topics/none",null).andExpect(status().isOk()));
        assertEquals(List.of("메모 키워드"),values(none.path("keywords"),"label"));assertTrue(none.path("keywords").get(0).path("topicId").isNull());
        assertTrue(none.path("topics").isEmpty());
        call("GET","/api/graph/topics/"+network,null,otherBearer).andExpect(status().isNotFound());
        call("GET","/api/graph/topics/"+UUID.randomUUID(),null).andExpect(status().isNotFound());
        call("GET","/api/graph/topics/not-a-uuid",null).andExpect(status().isBadRequest());
        JsonNode foreign=json(call("GET","/api/graph/topics",null,otherBearer).andExpect(status().isOk()));
        assertTrue(foreign.path("topics").isEmpty());assertEquals(0,foreign.path("unassigned").path("keywordCount").asInt());
        call("GET","/api/graph/topics",null,"invalid").andExpect(status().isUnauthorized());
    }

    /** 라벨 앞 "+"는 이해함으로 들어온(origin=generated) 개념을 뜻한다. relations는 라벨 쌍 사이의 관계다. */
    void analyze(UUID requested,Map<UUID,List<String>> keywords,List<List<String>> relations) {
        work.submit(userId,requested);Work job=work.claim();var items=new ArrayList<JsonNode>();var conceptIds=new HashMap<String,String>();var snapshots=new HashMap<String,JsonNode>();
        for(JsonNode doc:job.documents()) {
            int index=0;
            for(String raw:keywords.getOrDefault(UUID.fromString(doc.path("noteId").asString()),List.of())) {
                boolean generated=raw.startsWith("+");String label=generated?raw.substring(1):raw;String id=UUID.randomUUID().toString();
                ObjectNode item=mapper.createObjectNode();item.put("id",UUID.randomUUID().toString());item.set("snapshotId",doc.path("snapshotId"));item.put("kind","concept");item.put("ref","c"+index++);
                ObjectNode value=item.putObject("value");value.put("id",id).put("label",label).putArray("mentions");if(generated) value.put("origin","generated");
                items.add(item);conceptIds.putIfAbsent(label.strip(),id);snapshots.putIfAbsent(label.strip(),doc.path("snapshotId"));
            }
        }
        int index=0;
        for(List<String> pair:relations) {
            ObjectNode item=mapper.createObjectNode();item.put("id",UUID.randomUUID().toString());item.set("snapshotId",snapshots.get(pair.get(0)));item.put("kind","relation");item.put("ref","r"+index++);
            ObjectNode value=item.putObject("value");value.put("id",UUID.randomUUID().toString());
            value.putObject("source").put("id",conceptIds.get(pair.get(0))).put("label",pair.get(0));value.putObject("target").put("id",conceptIds.get(pair.get(1))).put("label",pair.get(1));
            value.putObject("type").put("code","causes").put("label","원인");value.put("origin","extracted");value.put("status","active");value.put("asHypothesis",false);value.putArray("evidence");
            items.add(item);
        }
        work.complete(job,new PipelineOutput(List.of(),items,List.of()));
    }
}
