package com.example.capstone.analysis;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
import com.example.capstone.analysis.client.AiHttpClient;
import com.example.capstone.analysis.client.AiResponseValidator;
import com.example.capstone.analysis.service.AnalysisWorkService;
import com.example.capstone.analysis.worker.AnalysisPipeline.PipelineOutput;
import com.example.capstone.analysis.worker.AnalysisPipeline.Work;
import com.example.capstone.support.ApiIntegrationSupport;
import com.example.capstone.user.domain.AppUser;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"capstone.ai.enabled=true", "capstone.ai.execution.worker-enabled=false",
        "capstone.ai.execution.poll-ms=3600000", "capstone.developer.emails=dev@example.com"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
class DeveloperAnalysisJobIntegrationTest extends ApiIntegrationSupport {
    private static final String TITLE = "비공개 노트 제목";
    private static final String BODY = "비공개 근거 문장입니다";
    private static final String CONCLUSION = "비공개 결론 문장";
    private static final String LIST = "/api/developer/analysis-jobs";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15");

    @Autowired
    AnalysisWorkService work;
    String developer;

    @BeforeEach
    void developer() {
        developer = tokens.issue(users.saveAndFlush(new AppUser("dev@example.com", "개발자", Instant.now())).getId());
    }

    @Test
    void 실패한_작업을_목록에서_찾아_상세에서_실패_단계와_코드를_본다() throws Exception {
        UUID note = note(TITLE + "-" + UUID.randomUUID(), null, BODY);
        UUID failed = failedJob(note);

        JsonNode list = json(call("GET", LIST + "?status=failed", null, developer).andExpect(status().isOk()));
        JsonNode row = find(list, failed);
        assertEquals("failed", row.path("status").asString());
        assertEquals("TIMEOUT", row.path("failureCode").asString());
        assertEquals(userId.toString(), row.path("user").path("id").asString());
        assertEquals("fixture@example.com", row.path("user").path("email").asString());
        assertEquals(note.toString(), row.path("noteId").asString());
        assertTrue(row.path("durationMs").asLong() >= 0);

        JsonNode detail = json(call("GET", LIST + "/" + failed, null, developer).andExpect(status().isOk()));
        assertEquals("TIMEOUT", detail.path("failureCode").asString());
        JsonNode stages = detail.path("stages");
        assertEquals(2, stages.size());
        assertEquals("FACTS", stages.get(0).path("operation").asString());
        assertTrue(stages.get(0).path("completed").asBoolean());
        assertEquals(1, stages.get(0).path("attempts").asInt());
        assertEquals(123, stages.get(0).path("durationMs").asLong());
        assertEquals("DERIVE", stages.get(1).path("operation").asString());
        assertFalse(stages.get(1).path("completed").asBoolean());
        JsonNode input = detail.path("inputs").valueStream()
                .filter(n -> n.path("noteId").asString().equals(note.toString())).findFirst().orElseThrow();
        assertFalse(input.path("factsCacheHit").asBoolean(true));
        assertEquals(0, detail.path("results").path("total").asLong());
        assertNoPrivateText(list, detail);
    }

    @Test
    void 성공한_작업의_결과_수와_피드백을_집계하고_원문은_주지_않는다() throws Exception {
        UUID first = note(TITLE + "-a-" + UUID.randomUUID(), null, BODY);
        note(TITLE + "-b-" + UUID.randomUUID(), null, BODY);
        UUID job = succeededJob(first);
        UUID result = jdbc.queryForObject("select id from analysis_result where job_id=?", UUID.class, job);
        jdbc.update("insert into recommendation_feedback(id,user_id,result_id,action) values (?,?,?,'confused')", UUID.randomUUID(), userId, result);
        jdbc.update("insert into recommendation_feedback(id,user_id,result_id,action) values (?,?,?,'understood')", UUID.randomUUID(), userId, result);

        JsonNode detail = json(call("GET", LIST + "/" + job, null, developer).andExpect(status().isOk()));
        assertEquals("succeeded", detail.path("status").asString());
        assertTrue(detail.path("failureCode").isNull());
        assertEquals(1, detail.path("results").path("total").asLong());
        assertEquals(1, detail.path("results").path("recommendable").asLong());
        assertEquals(1, detail.path("results").path("feedback").path("confused").asLong());
        assertEquals(1, detail.path("results").path("feedback").path("understood").asLong());
        assertEquals(0, detail.path("results").path("feedback").path("known").asLong());
        assertTrue(detail.path("inputs").size() >= 2);
        assertNoPrivateText(json(call("GET", LIST, null, developer).andExpect(status().isOk())), detail);
    }

    @Test
    void 상태와_기간으로_거르고_개수를_제한한다() throws Exception {
        UUID failed = failedJob(note("filter-a-" + UUID.randomUUID(), null, BODY));
        UUID succeeded = succeededJob(note("filter-b-" + UUID.randomUUID(), null, BODY));
        Instant failedAt = createdAt(failed), succeededAt = createdAt(succeeded);
        assertTrue(failedAt.isBefore(succeededAt));

        JsonNode onlyFailed = json(call("GET", LIST + "?status=failed", null, developer).andExpect(status().isOk()));
        assertTrue(contains(onlyFailed, failed));
        assertFalse(contains(onlyFailed, succeeded));
        onlyFailed.path("jobs").forEach(row -> assertEquals("failed", row.path("status").asString()));

        JsonNode before = json(call("GET", LIST + "?from=" + failedAt + "&to=" + succeededAt, null, developer).andExpect(status().isOk()));
        assertTrue(contains(before, failed));
        assertFalse(contains(before, succeeded));
        JsonNode after = json(call("GET", LIST + "?from=" + succeededAt, null, developer).andExpect(status().isOk()));
        assertTrue(contains(after, succeeded));
        assertFalse(contains(after, failed));

        JsonNode all = json(call("GET", LIST, null, developer).andExpect(status().isOk()));
        List<Instant> times = all.path("jobs").valueStream().map(row -> Instant.parse(row.path("createdAt").asString())).toList();
        for (int i = 1; i < times.size(); i++) {
            assertFalse(times.get(i).isAfter(times.get(i - 1)));
        }
        assertEquals(1, json(call("GET", LIST + "?limit=1", null, developer).andExpect(status().isOk())).path("jobs").size());

        for (String query : List.of("?status=done", "?limit=0", "?limit=101", "?limit=x", "?from=yesterday",
                "?from=" + succeededAt + "&to=" + failedAt)) {
            call("GET", LIST + query, null, developer).andExpect(status().isBadRequest());
        }
        call("GET", LIST + "/not-a-uuid", null, developer).andExpect(status().isBadRequest());
        call("GET", LIST + "/" + UUID.randomUUID(), null, developer).andExpect(status().isNotFound());
    }

    @Test
    void 개발자가_아닌_계정은_두_경로_모두_403이다() throws Exception {
        UUID job = failedJob(note("forbidden-" + UUID.randomUUID(), null, BODY));
        for (String path : List.of(LIST, LIST + "?status=done", LIST + "/" + job, LIST + "/not-a-uuid")) {
            JsonNode error = json(call("GET", path, null, bearer).andExpect(status().isForbidden()));
            assertEquals("FORBIDDEN", error.path("error").path("code").asString());
        }
        call("GET", LIST + "/" + job, null, otherBearer).andExpect(status().isForbidden());
    }

    /** FACTS 단계는 끝나고 DERIVE 호출 중에 시간 초과로 실패한 작업. */
    private UUID failedJob(UUID note) throws Exception {
        work.submit(userId, note);
        Work job = work.claim();
        ObjectNode facts = stageRequest(job);
        String key = "facts:" + UUID.randomUUID();
        work.reserveAttempt(job, key, "FACTS", facts);
        ObjectNode response = mapper.createObjectNode();
        response.putObject("context").put("noteId", note.toString());
        response.putArray("facts").addObject().put("quote", BODY);
        work.checkpoint(job, key, "FACTS", facts, new AiHttpClient.CallResult(response, 1, 123));
        work.reserveAttempt(job, "derive:test", "DERIVE", stageRequest(job));
        work.fail(job, "TIMEOUT");
        return job.id();
    }

    private UUID succeededJob(UUID note) throws Exception {
        work.submit(userId, note);
        Work job = work.claim();
        ObjectNode result = mapper.createObjectNode();
        UUID id = UUID.randomUUID();
        result.put("id", id.toString()).put("ref", "synthetic-result").put("identitySha256", AiResponseValidator.hash(id.toString()));
        result.put("kind", "derivation").put("conclusion", CONCLUSION);
        result.putArray("conditions").add("조건");
        result.putArray("trace").add("계산");
        result.putObject("detail").put("hypothesis", false);
        result.put("derivationStatus", "conclusion").put("soundness", "sound");
        var novelty = result.putArray("noveltyNoteIds");
        var evidence = result.putArray("evidence");
        result.putArray("dependsOn");
        for (var doc : job.documents()) {
            novelty.add(doc.path("noteId").asString());
            var span = evidence.addObject();
            span.set("snapshotId", doc.path("snapshotId"));
            span.put("claimRef", "premise").put("startOffset", 0).put("endOffset", 1).put("quote", doc.path("body").asString().substring(0, 1));
        }
        work.complete(job, new PipelineOutput(List.of(), List.of(), List.of(result)));
        return job.id();
    }

    private ObjectNode stageRequest(Work job) {
        ObjectNode request = mapper.createObjectNode();
        request.putObject("context").put("stageExecutionId", UUID.randomUUID().toString()).put("analysisJobId", job.id().toString());
        return request;
    }

    private Instant createdAt(UUID job) {
        return jdbc.queryForObject("select created_at from analysis_job where id=?", java.sql.Timestamp.class, job).toInstant();
    }

    private static JsonNode find(JsonNode list, UUID job) {
        return list.path("jobs").valueStream().filter(row -> row.path("id").asString().equals(job.toString())).findFirst().orElseThrow();
    }

    private static boolean contains(JsonNode list, UUID job) {
        return list.path("jobs").valueStream().anyMatch(row -> row.path("id").asString().equals(job.toString()));
    }

    private static void assertNoPrivateText(JsonNode... bodies) {
        for (JsonNode body : bodies) {
            String text = body.toString();
            for (String secret : List.of(TITLE, BODY, CONCLUSION, "비공개", "quote", "errorMessage", "response")) {
                assertFalse(text.contains(secret), secret);
            }
        }
    }
}
