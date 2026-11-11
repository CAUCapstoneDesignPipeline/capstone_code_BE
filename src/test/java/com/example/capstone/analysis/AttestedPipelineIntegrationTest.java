package com.example.capstone.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import com.example.capstone.analysis.client.AiResponseValidator;
import com.example.capstone.analysis.config.AiPipelineConfiguration;
import com.example.capstone.analysis.service.AnalysisWorkService;
import com.example.capstone.analysis.support.SyntheticPipelineServer;
import com.example.capstone.analysis.worker.GraphAnalysisPipeline;
import com.example.capstone.support.ApiIntegrationSupport;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 합성 stub이 아닌 attestation 경로로 10개 operation 전 구간을 돈다. 응답 meta는 AI 404dabf 모양이고
 * derive-sound-1은 expectedMeta 배열(판정 meta·규칙 meta)로 대조한다.
 */
@SpringBootTest(properties={"capstone.ai.enabled=true","capstone.ai.pipeline.synthetic-stub=false","capstone.ai.execution.budget-microunits=1000","capstone.ai.execution.currency=KRW","capstone.ai.execution.worker-enabled=false","capstone.ai.execution.poll-ms=3600000"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
class AttestedPipelineIntegrationTest extends ApiIntegrationSupport {
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    static final SyntheticPipelineServer server=new SyntheticPipelineServer();
    static final Path root=directory();
    static Path directory() {
        try {
            var dir=Files.createTempDirectory("ai-attested-");var json=new ObjectMapper();
            Files.writeString(dir.resolve("policy.json"),SyntheticPipelineServer.POLICY);Files.writeString(dir.resolve("ca.pem"),"synthetic-ca-fingerprint-input");
            var attestation=json.createObjectNode();
            var release=attestation.putObject("releaseManifest").put("kind","ai").put("image","example.invalid/ai@sha256:"+"1".repeat(64)).put("sourceSha","404dabf6cbe12621f4c86b84dc4dbda5b9750d5c");
            release.putObject("idf").put("sha256","3".repeat(64));
            release.putObject("model").put("profile","embeddings-bge-m3-1").put("name","BAAI/bge-m3").put("revision","5617a9f61b028005a4858fdac845db406aefb181");
            release.set("profiles",json.valueToTree(SyntheticPipelineServer.ATTESTED_META.keySet()));
            release.putObject("tls").put("caSha256",AiResponseValidator.hash(Files.readString(dir.resolve("ca.pem"))));
            var expected=attestation.putObject("expectedMeta");
            for(var entry:SyntheticPipelineServer.ATTESTED_META.entrySet()) expected.set(entry.getKey(),json.valueToTree(entry.getValue().size()==1?entry.getValue().getFirst():entry.getValue()));
            Files.writeString(dir.resolve("attestation.json"),attestation.toPrettyString());
            return dir;
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void ai(DynamicPropertyRegistry p) {
        p.add("capstone.ai.client.base-url",()->server.uri().toString());p.add("capstone.ai.client.service-token",()->SyntheticPipelineServer.TOKEN);
        p.add("capstone.ai.client.ca-certificate",()->root.resolve("ca.pem").toString());p.add("capstone.ai.pipeline.policy-file",()->root.resolve("policy.json").toString());
        p.add("capstone.ai.pipeline.attestation-file",()->root.resolve("attestation.json").toString());
        p.add("capstone.ai.pipeline.attestation-sha256",()->{ try { return AiResponseValidator.hash(Files.readString(root.resolve("attestation.json"))); } catch(Exception e) { throw new IllegalStateException(e); } });
    }
    @AfterAll static void cleanup() throws Exception { server.close();try(var files=Files.list(root)) { for(var file:files.toList()) Files.delete(file); }Files.delete(root); }
    @Autowired AnalysisWorkService work;
    @Autowired GraphAnalysisPipeline pipeline;
    @Autowired AiPipelineConfiguration configuration;
    @BeforeEach void reset() { server.mode="normal";server.attested=true;server.calculation=true;server.calculationState="sound";server.roleLabel=null;server.requests.clear(); }
    boolean capability() throws Exception { return json(call("GET","/api/capabilities",null).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())).path("analysisEnabled").asBoolean(); }
    UUID execute(UUID note) throws Exception {
        var result=call("POST","/api/notes/"+note+"/analysis",null);assertEquals(202,result.andReturn().getResponse().getStatus(),result.andReturn().getResponse().getContentAsString());
        UUID id=UUID.fromString(json(result).path("id").asString());var job=work.claim();assertEquals(id,job.id());work.complete(job,pipeline.run(job));
        assertEquals("succeeded",jdbc.queryForObject("select status from analysis_job where id=?",String.class,id));return id;
    }
    String body(int i) { return server.derivationFixture.path("request").path("documents").get(i).path("body").asString(); }

    @Test void everyOperationAndBothDeriveSoundMetasPassAttestationWithoutQuarantine() throws Exception {
        assertTrue(capability());
        UUID primary=note("앞",null,"A>B");note("뒤",null,"B>C");note("알고리즘",null,body(0));note("구현",null,body(1));
        UUID judged=execute(primary);
        var profiles=new TreeSet<String>();server.requests.forEach(r->profiles.add(r.path("profileVersion").asString()));
        assertEquals(new TreeSet<>(SyntheticPipelineServer.ATTESTED_META.keySet()),profiles);
        assertTrue(configuration.configured());assertTrue(capability());
        assertEquals(2,jdbc.queryForObject("select count(*) from analysis_result where job_id=? and kind='derivation' and detail->'meta'->>'provider'='local+anthropic'",Integer.class,judged));
        assertEquals(Set.of("facts-llm-3"),Set.copyOf(jdbc.queryForList("select distinct profile_version from analysis_fact where job_id=?",String.class,judged)));

        server.calculationState="unchecked";server.requests.clear();
        UUID unjudged=execute(primary);
        assertEquals(2,jdbc.queryForObject("select count(*) from analysis_result where job_id=? and kind='derivation' and detail->'meta'->>'provider'='local' and detail->'meta'->>'model'='llm'",Integer.class,unjudged));
        assertTrue(configuration.configured());assertTrue(capability());
    }
}
