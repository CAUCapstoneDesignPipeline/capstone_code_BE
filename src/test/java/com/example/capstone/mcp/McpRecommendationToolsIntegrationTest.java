package com.example.capstone.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
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

import tools.jackson.databind.JsonNode;

import com.example.capstone.analysis.service.AnalysisWorkService;
import com.example.capstone.analysis.support.SyntheticPipelineServer;
import com.example.capstone.analysis.worker.AnalysisWorker;
import com.example.capstone.analysis.worker.GraphAnalysisPipeline;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6 추천 도구, 로컬 대역 AI 서버를 켠 환경. 설계안 2.8 C7을 자동 시험으로 옮긴 것이다:
 * 요청 → pending → 분석 완료 → 조회했을 때 근거 quote가 저장된 값과 글자 단위로 같고 두 노트 제목이 나온다.
 */
@SpringBootTest(properties = {"capstone.mcp.enabled=true", "capstone.mcp.write-enabled=true",
        "capstone.ai.enabled=true", "capstone.ai.pipeline.synthetic-stub=true",
        "capstone.ai.execution.budget-microunits=1000", "capstone.ai.execution.currency=KRW",
        "capstone.ai.execution.worker-enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
class McpRecommendationToolsIntegrationTest extends McpIntegrationSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15");
    static final SyntheticPipelineServer server = new SyntheticPipelineServer();
    static final Path policy = policy();

    static Path policy() {
        try {
            Path path = Files.createTempFile("mcp-recommendation-policy-", ".json");
            Files.writeString(path, SyntheticPipelineServer.POLICY);
            return path;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry registry) {
        registry.add("capstone.ai.client.base-url", () -> server.uri().toString());
        registry.add("capstone.ai.client.service-token", () -> SyntheticPipelineServer.TOKEN);
        registry.add("capstone.ai.pipeline.policy-file", policy::toString);
    }

    @AfterAll
    static void cleanup() throws Exception {
        server.close();
        Files.delete(policy);
    }

    @Autowired AnalysisWorkService work;
    @Autowired GraphAnalysisPipeline pipeline;
    @Autowired AnalysisWorker worker;

    private void runQueuedJob() {
        var claimed = work.claim();
        work.complete(claimed, pipeline.run(claimed));
    }

    @Test
    void requestThenGetReturnsVerbatimEvidenceWithBothNoteTitles() throws Exception {
        server.mode = "normal";
        UUID first = note("앞", null, "A>B");
        note("뒤", null, "B>C");

        JsonNode requested = callTool("request_recommendations", Map.of("noteId", first.toString()));
        assertThat(requested.path("isError").asBoolean()).isFalse();
        JsonNode job = requested.path("structuredContent").path("job");
        assertThat(job.path("status").asString()).isEqualTo("pending");
        assertThat(job.path("noteId").asString()).isEqualTo(first.toString());
        assertThat(job.path("outcome").isNull()).isTrue();
        String jobId = job.path("id").asString();

        JsonNode again = callTool("request_recommendations", Map.of("noteId", first.toString())).path("structuredContent").path("job");
        assertThat(again.path("id").asString()).isEqualTo(jobId);

        runQueuedJob();

        JsonNode byJob = callTool("get_recommendations", Map.of("jobId", jobId)).path("structuredContent").path("job");
        JsonNode byNote = callTool("get_recommendations", Map.of("noteId", first.toString())).path("structuredContent").path("job");
        assertThat(byNote).isEqualTo(byJob);
        assertThat(byJob.path("status").asString()).isEqualTo("completed");
        assertThat(byJob.path("outcome").asString()).isEqualTo("RECOMMENDED");
        assertThat(byJob.path("recommendations")).isNotEmpty();

        var stored = jdbc.queryForList("select quote from analysis_result_evidence where user_id=?", String.class, userId);
        for (JsonNode card : byJob.path("recommendations")) {
            for (String side : new String[] {"evidenceA", "evidenceB"}) {
                JsonNode evidence = card.path(side);
                assertThat(stored).contains(evidence.path("quote").asString());
                assertThat(evidence.path("noteTitle").asString()).isIn("앞", "뒤");
            }
            assertThat(card.path("evidenceA").path("noteTitle").asString()).isEqualTo("앞");
            assertThat(card.path("freshness").asString()).isEqualTo("current");
        }

        JsonNode foreign = callTool(otherBearer, "get_recommendations", Map.of("jobId", jobId));
        assertThat(foreign.path("isError").asBoolean()).isTrue();
        assertThat(foreign.path("structuredContent").path("error").path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(foreign.toString()).doesNotContain("A>B");
    }

    @Test
    void failedAnalysisIsReportedAsFailureNotAsNoRecommendations() throws Exception {
        UUID first = note("앞", null, "A>B");
        note("뒤", null, "B>C");
        server.mode = "bad-composition";
        try {
            String jobId = callTool("request_recommendations", Map.of("noteId", first.toString()))
                    .path("structuredContent").path("job").path("id").asString();
            assertThat(worker.runOne()).isTrue();
            JsonNode job = callTool("get_recommendations", Map.of("jobId", jobId)).path("structuredContent").path("job");
            assertThat(job.path("status").asString()).isEqualTo("completed");
            assertThat(job.path("outcome").asString()).isEqualTo("RECOMMENDATION_FAILED");
            assertThat(job.path("recommendations")).isEmpty();
        } finally {
            server.mode = "normal";
        }
    }
}
