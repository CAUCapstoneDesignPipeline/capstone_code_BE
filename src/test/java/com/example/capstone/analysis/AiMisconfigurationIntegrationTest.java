package com.example.capstone.analysis;

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
import com.example.capstone.analysis.worker.AnalysisWorker;
import com.example.capstone.support.ApiIntegrationSupport;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"capstone.ai.enabled=true","capstone.ai.client.base-url=https://ai.invalid","capstone.ai.client.service-token=short","capstone.ai.execution.worker-enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
class AiMisconfigurationIntegrationTest extends ApiIntegrationSupport {
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    @Autowired AnalysisWorker worker;
    @Test void malformedOptionalAiKeepsCrudAndCancelsOnlyNewActiveJobs() throws Exception {
        UUID note=note("보존",null,"원문"),pending=UUID.randomUUID(),legacy=UUID.randomUUID();
        jdbc.update("insert into analysis_job(id,user_id,note_id,note_version,status,extractor,lifecycle_state,input_revision,graph_revision,deadline) values(?,?,?,0,'pending','policy:test','queued',0,0,clock_timestamp()+interval '10 minutes')",pending,userId,note);
        jdbc.update("insert into analysis_job(id,note_id,note_version,status,extractor) values(?,?,0,'succeeded','legacy')",legacy,note);
        worker.startup();assertEquals("cancelled",jdbc.queryForObject("select lifecycle_state from analysis_job where id=?",String.class,pending));assertEquals("succeeded",jdbc.queryForObject("select status from analysis_job where id=?",String.class,legacy));
        assertEquals(mapper.readTree("{\"analysisEnabled\":false,\"graphEnabled\":false,\"discoveriesEnabled\":false}"),json(call("GET","/api/capabilities",null)));
        var post=call("POST","/api/notes/"+note+"/analysis",null).andExpect(status().isServiceUnavailable());
        var envelope=mapper.createObjectNode().put("schemaVersion",1).put("syntheticOnly",true);envelope.putObject("producer").put("commit","working-tree").put("suite",getClass().getSimpleName());var captures=envelope.putArray("cases");
        captures.addObject().put("caseId","misconfigured-capability").put("origin","mockmvc").put("method","GET").put("pathTemplate","/capabilities").put("status",200).set("body",json(call("GET","/api/capabilities",null)));
        captures.addObject().put("caseId","misconfigured-post").put("origin","mockmvc").put("method","POST").put("pathTemplate","/notes/{noteId}/analysis").put("status",503).set("body",json(post));
        var target=java.nio.file.Path.of(".local/verification/results/public-capability-misconfigured.json");java.nio.file.Files.createDirectories(target.getParent());java.nio.file.Files.writeString(target,envelope.toPrettyString());
        call("PUT","/api/notes/"+note,"{\"title\":\"보존\",\"body\":\"수정\",\"version\":0}").andExpect(status().isOk());
        call("GET","/api/notes/"+note,null).andExpect(status().isOk());assertEquals(2,jdbc.queryForObject("select count(*) from analysis_job where note_id=?",Integer.class,note));
    }
}
