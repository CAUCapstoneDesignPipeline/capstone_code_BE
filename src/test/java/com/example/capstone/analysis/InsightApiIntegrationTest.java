package com.example.capstone.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import com.example.capstone.analysis.client.AiResponseValidator;
import com.example.capstone.analysis.service.AnalysisWorkService;
import com.example.capstone.analysis.worker.AnalysisPipeline.PipelineOutput;
import com.example.capstone.support.ApiIntegrationSupport;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
class InsightApiIntegrationTest extends ApiIntegrationSupport {
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    @Autowired AnalysisWorkService work;
    private final List<Map<String,Object>> captures=new ArrayList<>();
    private UUID seed(String statusValue,String soundness,int contributing) throws Exception {
        UUID first=note("first-"+UUID.randomUUID(),null,"가 근거"),second=note("second-"+UUID.randomUUID(),null,"나 근거");
        work.submit(userId,first);var job=work.claim();assertEquals(userId,job.userId());
        ObjectNode result=mapper.createObjectNode();UUID id=UUID.randomUUID();result.put("id",id.toString());result.put("ref","synthetic-result");result.put("identitySha256",AiResponseValidator.hash(id.toString()));
        result.put("kind","derivation");result.put("conclusion","두 원문이 기여한 합성 결론");result.putArray("conditions").add("조건 보존");result.putArray("trace").add("합성 계산 trace");result.putObject("detail").put("hypothesis",false);
        result.put("derivationStatus",statusValue);result.put("soundness",soundness);var novelty=result.putArray("noveltyNoteIds");var evidence=result.putArray("evidence");result.putArray("dependsOn");
        int count=0;
        for(var doc:job.documents()) if(doc.path("noteId").asString().equals(first.toString()) || doc.path("noteId").asString().equals(second.toString())) {
            novelty.add(doc.path("noteId").asString());
            if(count++<contributing) { var span=evidence.addObject();span.set("snapshotId",doc.path("snapshotId"));span.put("claimRef","premise");span.put("startOffset",0);span.put("endOffset",1);span.put("quote",doc.path("body").asString().substring(0,1)); }
        }
        work.complete(job,new PipelineOutput(List.of(),List.of(),List.of(result)));return id;
    }
    @Test void recommendationsOwnershipReviewReplayAndModifyPreserveIndependentStates() throws Exception {
        UUID id=seed("conclusion","sound",2);seed("held","uncertain",2);seed("known","sound",2);seed("conclusion","sound",1);
        JsonNode list=capture("list","GET","/candidates",call("GET","/api/candidates",null).andExpect(status().isOk()));
        assertEquals(1,list.path("results").size());assertEquals(id.toString(),list.path("results").get(0).path("id").asString());assertTrue(list.path("unsearchedOutsideScope").asBoolean());
        call("GET","/api/candidates/"+id,null,otherBearer).andExpect(status().isNotFound());
        assertTrue(json(call("GET","/api/candidates",null,otherBearer)).path("results").isEmpty());
        String accept=review(1,"accept",null,"new_understanding",null,"accept-key");
        JsonNode event=capture("accept","POST","/candidates/{candidateId}/review",call("POST","/api/candidates/"+id+"/review",accept).andExpect(status().isCreated()));
        assertEquals(event,json(call("POST","/api/candidates/"+id+"/review",accept).andExpect(status().isCreated())));
        call("POST","/api/candidates/"+id+"/review",review(1,"reject","wrong",null,null,"accept-key")).andExpect(status().isConflict());
        JsonNode accepted=json(call("GET","/api/candidates/"+id,null));assertEquals("sound",accepted.path("soundness").asString());assertEquals("accepted",accepted.path("reviewStatus").asString());
        capture("modify","POST","/candidates/{candidateId}/review",call("POST","/api/candidates/"+id+"/review",review(2,"modify",null,null,"사용자 수정 결론","modify-key")).andExpect(status().isCreated()));
        JsonNode modified=capture("detail","GET","/candidates/{candidateId}",call("GET","/api/candidates/"+id,null).andExpect(status().isOk()));assertEquals("unchecked",modified.path("soundness").asString());assertEquals(3,modified.path("revision").asInt());
        assertTrue(json(call("GET","/api/candidates",null)).path("results").isEmpty());
        JsonNode history=capture("history","GET","/reviews",call("GET","/api/reviews",null).andExpect(status().isOk()));assertEquals(2,history.path("reviews").size());
        assertEquals(0,jdbc.queryForObject("select count(*) from relation where origin='confirmed'",Integer.class));
        writeCaptures();
    }
    @Test void repeatedSemanticIdentityUsesLatestProjectionWhileHistoryAndDifferentConditionsRemain() throws Exception {
        UUID first=seed("conclusion","sound",2),second=seed("conclusion","sound",2),different=seed("conclusion","sound",2);
        String identity=AiResponseValidator.hash("same-condition-and-scope");
        jdbc.update("update analysis_result set identity_sha256=? where id in (?,?)",identity,first,second);
        JsonNode list=json(call("GET","/api/candidates",null));assertEquals(2,list.path("results").size());
        assertEquals(3,json(call("GET","/api/candidates?view=history",null)).path("results").size());
        assertTrue(list.path("results").toString().contains(different.toString()));
    }
    @Test void concurrentReviewsHaveOneWinnerAndUnknownOrMissingFieldsAreRejected() throws Exception {
        UUID id=seed("conclusion","sound",2);CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->{start.await();return call("POST","/api/candidates/"+id+"/review",review(1,"accept",null,null,null,"first")).andReturn().getResponse().getStatus();});
            var second=executor.submit(()->{start.await();return call("POST","/api/candidates/"+id+"/review",review(1,"reject","wrong",null,null,"second")).andReturn().getResponse().getStatus();});
            start.countDown();assertEquals(java.util.Set.of(201,409),java.util.Set.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS)));
        }
        assertEquals(1,jdbc.queryForObject("select count(*) from analysis_review_event where result_id=?",Integer.class,id));
        ObjectNode request=(ObjectNode)mapper.readTree(review(2,"accept",null,null,null,"bad"));request.put("unknown","extra");
        call("POST","/api/candidates/"+id+"/review",request.toString()).andExpect(status().isBadRequest());request.remove("unknown");request.remove("reason");
        call("POST","/api/candidates/"+id+"/review",request.toString()).andExpect(status().isBadRequest());
    }
    @Test void graphReadPathsRetainAuthenticationOwnershipAndEmptyGraphContract() throws Exception {
        UUID note=note("graph",null,"본문");
        JsonNode graph=capture("note-graph","GET","/notes/{noteId}/graph",call("GET","/api/notes/"+note+"/graph",null).andExpect(status().isOk()));
        assertTrue(graph.path("analysis").isNull());assertTrue(graph.path("concepts").isArray());assertTrue(graph.path("relations").isArray());
        capture("graph","GET","/graph",call("GET","/api/graph?scope=note&noteId="+note,null).andExpect(status().isOk()));
        capture("types","GET","/relation-types",call("GET","/api/relation-types",null).andExpect(status().isOk()));
        call("GET","/api/notes/"+note+"/graph",null,otherBearer).andExpect(status().isNotFound());
        call("GET","/api/graph?scope=topic",null).andExpect(status().isBadRequest());writeCaptures();
    }
    @Test void nonemptyGraphPreservesOwnedEvidenceAndRelationDetail() throws Exception {
        UUID a=note("graph-a",null,"가"),b=note("graph-b",null,"나");work.submit(userId,a);var job=work.claim();
        JsonNode sa=job.documents().stream().filter(d->d.path("noteId").asString().equals(a.toString())).findFirst().orElseThrow();
        JsonNode sb=job.documents().stream().filter(d->d.path("noteId").asString().equals(b.toString())).findFirst().orElseThrow();
        UUID ca=UUID.randomUUID(),cb=UUID.randomUUID(),relationId=UUID.randomUUID();
        var graph=new ArrayList<JsonNode>();
        for(var pair:List.of(Map.entry(sa,ca),Map.entry(sb,cb))) {
            var value=mapper.createObjectNode();value.put("id",pair.getValue().toString());value.put("label",pair.getKey().path("body").asString());
            var mention=value.putArray("mentions").addObject();mention.put("surface",pair.getKey().path("body").asString());mention.put("startOffset",0);mention.put("endOffset",1);
            graph.add(item(pair.getKey(),"concept",value));
        }
        var relation=mapper.createObjectNode();relation.put("id",relationId.toString());relation.putObject("source").put("id",ca.toString()).put("label","가");relation.putObject("target").put("id",cb.toString()).put("label","나");
        relation.putObject("type").put("code","supports").put("label","지지");relation.put("origin","extracted");relation.put("status","active");relation.put("asHypothesis",false);
        var span=relation.putArray("evidence").addObject();span.put("id",UUID.randomUUID().toString());span.put("noteId",a.toString());span.put("noteTitle","graph-a");span.set("topic",sa.path("topic"));span.put("noteVersion",0);span.put("startOffset",0);span.put("endOffset",1);span.put("quote","가");span.put("status","valid");
        graph.add(item(sa,"relation",relation));work.complete(job,new PipelineOutput(List.of(),graph,List.of()));
        JsonNode noteGraph=capture("nonempty-note-graph","GET","/notes/{noteId}/graph",call("GET","/api/notes/"+a+"/graph",null).andExpect(status().isOk()));
        assertEquals(1,noteGraph.path("concepts").size());assertEquals(1,noteGraph.path("relations").size());assertEquals(a.toString(),noteGraph.path("relations").get(0).path("evidence").get(0).path("noteId").asString());
        JsonNode global=capture("nonempty-graph","GET","/graph",call("GET","/api/graph",null).andExpect(status().isOk()));assertEquals(2,global.path("nodes").size());assertEquals(1,global.path("edges").size());
        capture("relation-detail","GET","/relations/{relationId}",call("GET","/api/relations/"+relationId,null).andExpect(status().isOk()));
        call("GET","/api/relations/"+relationId,null,otherBearer).andExpect(status().isNotFound());
        call("DELETE","/api/notes/"+a,null).andExpect(status().isConflict());writeCaptures();
    }
    private ObjectNode item(JsonNode doc,String kind,JsonNode value) {
        var item=mapper.createObjectNode();item.put("id",UUID.randomUUID().toString());item.set("snapshotId",doc.path("snapshotId"));item.put("kind",kind);item.put("ref",value.path("id").asString());item.set("value",value);return item;
    }
    private String review(int revision,String action,String reason,String learning,String edited,String key) {
        var n=mapper.createObjectNode();n.put("expectedRevision",revision);n.put("action",action);n.put("reason",reason);n.put("learning",learning);n.put("editedConclusion",edited);n.put("idempotencyKey",key);return n.toString();
    }
    private JsonNode capture(String name,String method,String path,ResultActions result) throws Exception {
        JsonNode body=json(result);captures.add(Map.of("caseId",name,"origin","mockmvc","method",method,"pathTemplate",path,"status",result.andReturn().getResponse().getStatus(),"body",body));return body;
    }
    private void writeCaptures() throws Exception {
        Path path=Path.of(".local/verification/results/public-"+captures.getFirst().get("caseId")+".json");Files.createDirectories(path.getParent());
        Files.writeString(path,mapper.writeValueAsString(Map.of("schemaVersion",1,"syntheticOnly",true,"producer",Map.of("commit","working-tree","suite","InsightApiIntegrationTest"),"cases",captures)));
    }
}
