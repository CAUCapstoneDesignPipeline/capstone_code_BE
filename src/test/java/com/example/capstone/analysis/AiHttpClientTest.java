package com.example.capstone.analysis;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import com.example.capstone.analysis.client.AiCallException;
import com.example.capstone.analysis.client.AiHttpClient;
import com.example.capstone.analysis.client.AiOperation;
import com.example.capstone.analysis.client.AiResponseValidator;
import com.example.capstone.analysis.client.AiSchemaValidator;
import com.example.capstone.analysis.config.AiClientProperties;
import com.example.capstone.analysis.support.ScriptedAiServer;
import static org.junit.jupiter.api.Assertions.*;

class AiHttpClientTest {
    private static final String TOKEN = "synthetic-service-token-only";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AiSchemaValidator schemas = new AiSchemaValidator(mapper);
    private final AiResponseValidator validator = new AiResponseValidator(schemas);
    private JsonNode fixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/ai-contract/facts-normal.json")) { return mapper.readTree(in); }
    }
    private AiHttpClient client(ScriptedAiServer server, String token, Duration timeout) {
        var env = new MockEnvironment(); env.setActiveProfiles("test");
        return new AiHttpClient(new AiClientProperties(server.baseUri().toString(), token, null,
                Duration.ofSeconds(1), timeout, timeout, 3, 2, Duration.ZERO), mapper, schemas, validator, env);
    }
    @Test void retriesAvailabilityWithIdenticalPayloadAndLogicalRequestId() throws Exception {
        var f = fixture();
        try (var server = new ScriptedAiServer(TOKEN, AiHttpClient.REQUEST_LIMIT)) {
            server.enqueue(new ScriptedAiServer.Reply(503, "<html>maintenance</html>", Map.of()));
            server.enqueue(new ScriptedAiServer.Reply(200, f.path("response").toString(), Map.of()));
            var result = client(server, TOKEN, Duration.ofSeconds(2)).call(AiOperation.FACTS, f.path("request"), Instant.now().plusSeconds(5));
            var first = server.takeRequest(Duration.ofSeconds(1)); var second = server.takeRequest(Duration.ofSeconds(1));
            assertEquals(2, result.attempts()); assertArrayEquals(first.body(), second.body());
            assertEquals(first.requestId(), second.requestId()); assertNotEquals(first.attemptId(), second.attemptId());
            assertEquals(f.path("response"), result.body()); assertFalse(result.toString().contains("알고리즘"));
        }
    }
    @Test void authenticationIsNotRetriedAndHasNoRemotePayload() throws Exception {
        try (var server = new ScriptedAiServer(TOKEN, AiHttpClient.REQUEST_LIMIT)) {
            var failure = assertThrows(AiCallException.class, () -> client(server, "wrong-synthetic-token", Duration.ofSeconds(1))
                    .call(AiOperation.FACTS, fixture().path("request"), Instant.now().plusSeconds(3)));
            assertEquals(AiCallException.Reason.AUTHENTICATION, failure.reason()); assertFalse(failure.getMessage().contains(TOKEN));
        }
    }
    @Test void strictSchemaContextProfileReferenceAndUtf16BoundariesReject() throws Exception {
        var f = fixture(); var request = f.path("request");
        validator.validate(AiOperation.FACTS, request, f.path("response"));
        for (String mutation : new String[]{"context", "profile", "owner", "quote", "unknown", "surrogate", "type"}) {
            ObjectNode response = (ObjectNode) f.path("response").deepCopy();
            ObjectNode evidence = (ObjectNode) response.path("facts").get(0).path("evidence");
            switch (mutation) {
                case "context" -> ((ObjectNode) response.path("context")).put("inputRevision", 999);
                case "profile" -> ((ObjectNode) response.path("meta")).put("profileVersion", "unsupported");
                case "owner" -> evidence.put("noteId", "00000000-0000-4000-8000-000000000099");
                case "quote" -> evidence.put("quote", "invented");
                case "unknown" -> response.put("untrustedExtra", "must not persist");
                case "surrogate" -> { evidence.put("startOffset", 8); evidence.put("endOffset", 9); evidence.put("quote", "🧠"); }
                case "type" -> evidence.put("startOffset", "0");
                default -> fail();
            }
            assertThrows(AiCallException.class, () -> validator.validate(AiOperation.FACTS, request, response), mutation);
        }
    }
    @Test void readinessRequiresEveryProfileAndNeverTrustsHttp200Alone() throws Exception {
        try (var server = new ScriptedAiServer(TOKEN, AiHttpClient.REQUEST_LIMIT)) {
            server.enqueue(new ScriptedAiServer.Reply(200, "{\"status\":\"ready\",\"schemaVersion\":\"1.0\",\"profileVersions\":[]}", Map.of()));
            assertEquals(AiCallException.Reason.PROFILE, assertThrows(AiCallException.class,
                    () -> client(server, TOKEN, Duration.ofSeconds(1)).ready(Set.of("embeddings-bge-m3-1"))).reason());
        }
    }
    @Test void deadlineCoversResponseBodyAndMalformedSuccessNeverRetries() throws Exception {
        try (var server = new ScriptedAiServer(TOKEN, AiHttpClient.REQUEST_LIMIT)) {
            var reply = ScriptedAiServer.Reply.gated(200, "{}"); server.enqueue(reply);
            try {
                assertEquals(AiCallException.Reason.TIMEOUT, assertThrows(AiCallException.class,
                        () -> client(server, TOKEN, Duration.ofMillis(100)).call(AiOperation.FACTS,
                                fixture().path("request"), Instant.now().plusMillis(80))).reason());
            } finally { reply.release(); }
        }
        try (var server = new ScriptedAiServer(TOKEN, AiHttpClient.REQUEST_LIMIT)) {
            server.enqueue(new ScriptedAiServer.Reply(200, "{broken", Map.of()));
            assertEquals(AiCallException.Reason.INVALID_RESPONSE, assertThrows(AiCallException.class,
                    () -> client(server, TOKEN, Duration.ofSeconds(1)).call(AiOperation.FACTS,
                            fixture().path("request"), Instant.now().plusSeconds(2))).reason());
        }
    }
    @Test void cleartextOutsideTestAndMissingPrivateCaAreRejected() {
        var p = new AiClientProperties("http://127.0.0.1:1", TOKEN, null, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, Duration.ZERO);
        assertThrows(AiCallException.class, () -> new AiHttpClient(p, mapper, schemas, validator, new MockEnvironment()));
        var tls = new AiClientProperties("https://ai.internal", TOKEN, null, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, Duration.ZERO);
        assertThrows(AiCallException.class, () -> new AiHttpClient(tls, mapper, schemas, validator, new MockEnvironment()));
    }
}
