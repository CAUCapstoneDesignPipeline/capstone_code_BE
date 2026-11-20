package com.example.capstone.mcp;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M7: 도구 전체에 걸친 권한 격리와 기록(설계안 2.8 P1~P3·P5·P8).
 * P4(다른 사용자의 추천 작업)는 McpRecommendationToolsIntegrationTest, 운영 프로필의 dev 토큰은 McpProductionAuthWebMvcTest에서 본다.
 */
@SpringBootTest(properties = {"capstone.mcp.enabled=true", "capstone.mcp.write-enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class McpIsolationIntegrationTest extends McpIntegrationSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15");

    @Autowired JwtEncoder encoder;

    private UUID foreignNote(String title, UUID topicId) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("title", title);
        body.put("body", "남의 본문");
        body.put("topicId", topicId);
        return UUID.fromString(json(call("POST", "/api/notes", mapper.writeValueAsString(body), otherBearer)).path("id").asString());
    }

    private static JsonNode error(JsonNode result) {
        return result.path("structuredContent").path("error");
    }

    @Test
    void p1OtherUsersNoteLooksExactlyLikeAMissingOne() throws Exception {
        UUID foreign = foreignNote("남의 비밀 노트", null);
        UUID missing = UUID.randomUUID();
        List<Map<String, Object>> calls = List.of(
                Map.of("tool", "get_note", "args", Map.of()),
                Map.of("tool", "update_note", "args", Map.of("title", "덮어쓰기", "body", "x", "version", 0)),
                Map.of("tool", "rename_note", "args", Map.of("title", "새 제목", "version", 0)),
                Map.of("tool", "get_recommendations", "args", Map.of()));
        for (Map<String, Object> spec : calls) {
            @SuppressWarnings("unchecked")
            Map<String, Object> extra = (Map<String, Object>) spec.get("args");
            var foreignArgs = new HashMap<String, Object>(extra);
            foreignArgs.put("noteId", foreign.toString());
            var missingArgs = new HashMap<String, Object>(extra);
            missingArgs.put("noteId", missing.toString());
            JsonNode a = callTool((String) spec.get("tool"), foreignArgs);
            JsonNode b = callTool((String) spec.get("tool"), missingArgs);
            assertThat(error(a).path("code").asString()).as((String) spec.get("tool")).isEqualTo("NOT_FOUND");
            assertThat(error(a)).as((String) spec.get("tool")).isEqualTo(error(b));
            assertThat(a.toString()).doesNotContain("남의");
        }
        JsonNode request = callTool("request_recommendations", Map.of("noteId", foreign.toString()));
        assertThat(error(request).path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(json(call("GET", "/api/notes/" + foreign, null, otherBearer)).path("title").asString()).isEqualTo("남의 비밀 노트");
    }

    @Test
    void p2ListAndSearchShowOnlyOwnData() throws Exception {
        UUID theirs = UUID.fromString(json(call("POST", "/api/topics", "{\"name\":\"남의 주제\"}", otherBearer)).path("id").asString());
        foreignNote("남의 페이징", theirs);
        note("내 페이징", topic("내 주제"), "페이징");
        String topics = callTool("list_topics", Map.of()).path("structuredContent").toString();
        String search = callTool("search_notes", Map.of("query", "페이징")).path("structuredContent").toString();
        assertThat(topics).contains("내 주제").doesNotContain("남의");
        assertThat(search).contains("내 페이징").doesNotContain("남의");
    }

    @Test
    void p3MoveNeverTouchesOtherUsersNotesOrTopics() throws Exception {
        UUID theirTopic = UUID.fromString(json(call("POST", "/api/topics", "{\"name\":\"남의 주제\"}", otherBearer)).path("id").asString());
        UUID theirNote = foreignNote("남의 노트", null);
        UUID mine = note("내 노트", null, "본문");
        JsonNode mixed = callTool("move_notes", Map.of("noteIds", List.of(mine.toString(), theirNote.toString()),
                "topicId", topic("내 주제").toString())).path("structuredContent");
        assertThat(mixed.path("moved").size()).isEqualTo(1);
        assertThat(mixed.path("failed").get(0).path("noteId").asString()).isEqualTo(theirNote.toString());
        assertThat(mixed.path("failed").get(0).path("code").asString()).isEqualTo("NOT_FOUND");
        JsonNode intoTheirs = callTool("move_notes", Map.of("noteIds", List.of(mine.toString()), "topicId", theirTopic.toString()));
        assertThat(intoTheirs.path("isError").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from note where topic_id=?", Long.class, theirTopic)).isZero();
        assertThat(jdbc.queryForObject("select topic_id from note where id=?", UUID.class, theirNote)).isNull();
    }

    @Test
    void p5ExpiredOrForgedTokensAreRejectedBeforeAnyTool() throws Exception {
        Instant past = Instant.now().minusSeconds(3600);
        String expired = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer("capstone-be").subject(userId.toString()).issuedAt(past)
                        .expiresAt(past.plusSeconds(1800)).build())).getTokenValue();
        String[] parts = bearer.split("\\.");
        String forged = parts[0] + "." + parts[1] + "." + new StringBuilder(parts[2]).reverse();
        var payload = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list", "params", Map.of());
        for (String token : List.of(expired, forged, "not-a-token")) {
            mcpRaw(token, payload).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void p8LogsRecordCallsWithoutTitlesBodiesQueriesOrTokens(CapturedOutput output) throws Exception {
        String title = "로그금지제목" + UUID.randomUUID();
        String body = "로그금지본문" + UUID.randomUUID();
        String query = "로그금지검색어" + UUID.randomUUID();
        JsonNode created = callTool("create_note", Map.of("title", title, "body", body)).path("structuredContent");
        String noteId = created.path("id").asString();
        callTool("search_notes", Map.of("query", query));
        callTool("get_note", Map.of("noteId", noteId));
        callTool("rename_note", Map.of("noteId", noteId, "title", title + "2", "version", 0));
        String logs = output.getAll();
        assertThat(logs).contains("mcp tool call", "tool=create_note", "tool=search_notes", "tool=get_note", "tool=rename_note",
                "result=OK", "user=" + userId, "targets=[" + noteId + "]", "targetCount=1");
        assertThat(logs).doesNotContain(title, body, query, bearer, "로그금지");
    }
}
