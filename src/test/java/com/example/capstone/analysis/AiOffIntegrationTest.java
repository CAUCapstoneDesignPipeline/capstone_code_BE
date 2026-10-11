package com.example.capstone.analysis;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
import com.example.capstone.support.ApiIntegrationSupport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("prod")
@Testcontainers
class AiOffIntegrationTest extends ApiIntegrationSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15");
    private static final String TEST_KEY = key();

    private static String key() {
        byte[] value = new byte[48];
        new SecureRandom().nextBytes(value);
        return Base64.getEncoder().encodeToString(value);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("capstone.auth.jwt.secret", () -> TEST_KEY);
        registry.add("capstone.ai.enabled", () -> false);
        registry.add("capstone.auth.google.enabled", () -> false);
    }

    @Test
    void publicCapabilitiesIgnoreInvalidBearerWithoutOpeningOtherMethodsOrPaths() throws Exception {
        String expected = "{\"analysisEnabled\":false,\"graphEnabled\":false,\"discoveriesEnabled\":false}";
        mvc.perform(get("/api/capabilities")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(content().json(expected));
        mvc.perform(get("/api/capabilities").header("Authorization", "Bearer invalid"))
                .andExpect(status().isOk()).andExpect(content().json(expected));
        mvc.perform(post("/api/capabilities")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/capabilities/private")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notes").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticationPrecedesParsingAndOwnershipForBothAnalysisMethods() throws Exception {
        mvc.perform(get("/api/notes/not-a-uuid/analysis")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/notes/not-a-uuid/analysis")).andExpect(status().isUnauthorized());
        UUID id = note("인증 경계", null, "원문");
        for (String method : List.of("GET", "POST")) {
            call(method, path(id), null, "invalid").andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
            call(method, path(id), null, otherBearer).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
            call(method, path(UUID.randomUUID()), null).andExpect(status().isNotFound());
            call(method, "/api/notes/not-a-uuid/analysis", null).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
            call(method, "/api/notes/0-0-0-0-0/analysis", null).andExpect(status().isBadRequest());
        }
    }

    @Test
    void disabledPostNeverCreatesOrMutatesAnExistingPendingJob() throws Exception {
        UUID id = note("분석 차단", null, "원문");
        assertThat(jobCount(id)).isZero();
        call("POST", path(id), null).andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(content().json("{\"error\":{\"code\":\"AI_UNAVAILABLE\",\"message\":\"AI 분석은 아직 사용할 수 없습니다.\",\"details\":{\"reason\":\"NOT_DEPLOYED\"}}}"));
        assertThat(jobCount(id)).isZero();
        insertJob(id, UUID.randomUUID(), "pending", "2026-01-01T00:00:00Z");
        var before = jdbc.queryForList("select * from analysis_job where note_id=?", id);
        call("POST", path(id), null).andExpect(status().isServiceUnavailable());
        call("GET", path(id), null).andExpect(jsonPath("$.job.status").value("pending"));
        assertThat(jdbc.queryForList("select * from analysis_job where note_id=?", id)).isEqualTo(before);
    }

    @Test
    void emptyHistoryIsExplicitNullAndCoreNoteOperationsDoNotEnqueueJobs() throws Exception {
        UUID id = note("노트 저장", null, "원문");
        call("GET", path(id), null).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"job\":null}"));
        call("PUT", "/api/notes/" + id, "{\"title\":\"저장 수정\",\"body\":\"바뀐 원문\",\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        UUID topic = topic("이동 대상");
        call("PUT", "/api/notes/" + id + "/topic", "{\"topicId\":\"" + topic + "\"}")
                .andExpect(status().isOk());
        call("GET", "/api/notes?q=바뀐", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.notes[0].id").value(id.toString()));
        assertThat(jobCount(id)).isZero();
        call("DELETE", "/api/notes/" + id, null).andExpect(status().isNoContent());
        assertThat(jobCount(id)).isZero();
    }

    @Test
    void readsActualStatusAndTimesForAllFourStatesWithoutExposingInternalErrors() throws Exception {
        for (String state : List.of("pending", "running", "succeeded", "failed")) {
            UUID id = note("기록 " + state, null, "원문");
            UUID job = UUID.randomUUID();
            insertJob(id, job, state, "2026-01-01T00:00:00Z");
            var before = jdbc.queryForList("select * from analysis_job where note_id=?", id);
            var response = json(call("GET", path(id), null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.job.id").value(job.toString()))
                    .andExpect(jsonPath("$.job.noteId").value(id.toString()))
                    .andExpect(jsonPath("$.job.noteVersion").value(7))
                    .andExpect(jsonPath("$.job.status").value(state))
                    .andExpect(jsonPath("$.job.createdAt").value("2026-01-01T00:00:00Z"))
                    .andExpect(jsonPath("$.job.errorMessage").doesNotExist()));
            assertThat(response.path("job").path("startedAt").isNull()).isTrue();
            assertThat(response.path("job").path("finishedAt").isNull()).isTrue();
            assertThat(jdbc.queryForList("select * from analysis_job where note_id=?", id)).isEqualTo(before);
        }
    }

    @Test
    void choosesLatestByCreationTimeThenIdAndPreservesUtcTimes() throws Exception {
        UUID id = note("최근 기록", null, "원문");
        UUID expected = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        insertJob(id, UUID.randomUUID(), "succeeded", "2025-12-01T00:00:00Z");
        insertJob(id, UUID.fromString("00000000-0000-0000-0000-000000000001"), "failed", "2026-01-01T00:00:00Z");
        insertJob(id, expected, "succeeded", "2026-01-01T00:00:00Z");
        jdbc.update("update analysis_job set started_at='2026-01-01T09:01:00+09:00',finished_at='2026-01-01T09:02:00+09:00' where id=?", expected);
        call("GET", path(id), null).andExpect(jsonPath("$.job.id").value(expected.toString()))
                .andExpect(jsonPath("$.job.startedAt").value("2026-01-01T00:01:00Z"))
                .andExpect(jsonPath("$.job.finishedAt").value("2026-01-01T00:02:00Z"));
    }

    private void insertJob(UUID note, UUID job, String status, String created) {
        jdbc.update("insert into analysis_job(id,note_id,note_version,status,extractor,error_message,created_at) values(?,?,7,?,'test-fixture','internal-only-message',?)",
                job, note, status, java.sql.Timestamp.from(Instant.parse(created)));
    }

    private long jobCount(UUID note) {
        return jdbc.queryForObject("select count(*) from analysis_job where note_id=?", Long.class, note);
    }

    private String path(UUID note) {
        return "/api/notes/" + note + "/analysis";
    }
}
