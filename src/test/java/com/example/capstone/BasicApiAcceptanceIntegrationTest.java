package com.example.capstone;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class BasicApiAcceptanceIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15");

    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String APP_ORIGIN = "http://localhost:5173";

    @Test
    void isolatesUsersAndRestoresSessionAndNotesAfterRestart() throws Exception {
        String account = "acceptance-" + UUID.randomUUID() + "@example.com";
        String cookie;
        String otherDeviceCookie;
        String userId;
        String noteId;
        try (var context = start()) {
            String base = base(context);
            var login = request(base, "POST", "/api/auth/dev/token",
                    Map.of("email", account, "issueRefreshCookie", true), null, null, null);
            check(login, 200);
            JsonNode session = json(login);
            String token = session.path("accessToken").asString();
            userId = session.path("user").path("id").asString();
            cookie = refreshCookie(login);
            var secondDevice = request(base, "POST", "/api/auth/dev/token",
                    Map.of("email", account, "issueRefreshCookie", true), null, null, null);
            check(secondDevice, 200);
            assertThat(json(secondDevice).path("user").path("id").asString()).isEqualTo(userId);
            otherDeviceCookie = refreshCookie(secondDevice);

            var otherLogin = request(base, "POST", "/api/auth/dev/token",
                    Map.of("email", "other-" + UUID.randomUUID() + "@example.com"), null, null, null);
            check(otherLogin, 200);
            String otherToken = json(otherLogin).path("accessToken").asString();
            assertThat(json(otherLogin).path("user").path("id").asString()).isNotEqualTo(userId);

            check(request(base, "PUT", "/api/topics/order", Map.of("topicIds", List.of()), token, null, null), 200);
            String topic = create(base, "/api/topics", Map.of("name", "DB"), token);
            String destination = create(base, "/api/topics", Map.of("name", "네트워크"), token);
            noteId = create(base, "/api/notes", Map.of("topicId", topic, "title", "가", "body", "# 원문\n검색어 근거"), token);
            assertThat(json(get(base, "/api/notes/" + noteId, token)).path("title").asString()).isEqualTo("가");
            String duplicate = create(base, "/api/notes", Map.of("title", "가"), token);
            var blocked = request(base, "DELETE", "/api/topics/" + topic, null, token, null, null);
            check(blocked, 409);
            assertThat(json(blocked).path("error").path("code").asString()).isEqualTo("NOTE_TITLE_TAKEN");
            assertThat(json(get(base, "/api/notes/" + noteId, token)).path("topicId").asString()).isEqualTo(topic);

            check(request(base, "GET", "/api/notes/" + noteId, null, otherToken, null, null), 404);
            check(request(base, "GET", "/api/notes?topicId=" + topic, null, otherToken, null, null), 404);
            assertThat(json(get(base, "/api/notes?q=" + encode("검색어"), otherToken)).path("notes").isEmpty()).isTrue();
            var results = json(get(base, "/api/notes?topicId=" + topic + "&q=" + encode("검색어"), token)).path("notes");
            assertThat(results.size()).isEqualTo(1);
            assertThat(results.get(0).has("body")).isFalse();

            CyclicBarrier saveGate = new CyclicBarrier(2);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(() -> { saveGate.await(); return request(base, "PUT", "/api/notes/" + results.get(0).path("id").asString(),
                        Map.of("title", "가", "body", "첫 입력", "version", 0), token, null, null); });
                var second = executor.submit(() -> { saveGate.await(); return request(base, "PUT", "/api/notes/" + results.get(0).path("id").asString(),
                        Map.of("title", "가", "body", "둘째 입력", "version", 0), token, null, null); });
                var a = first.get(10, TimeUnit.SECONDS);
                var b = second.get(10, TimeUnit.SECONDS);
                assertThat(List.of(a.statusCode(), b.statusCode())).containsExactlyInAnyOrder(200, 409);
                var conflict = json(a.statusCode() == 409 ? a : b).path("error");
                assertThat(conflict.path("code").asString()).isEqualTo("NOTE_CONFLICT");
                assertThat(conflict.path("details").path("current").path("version").asInt()).isEqualTo(1);
            }

            String savedNoteId = noteId;
            CyclicBarrier moveGate = new CyclicBarrier(2);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var saved = executor.submit(() -> { moveGate.await(); return request(base, "PUT", "/api/notes/" + savedNoteId,
                        Map.of("title", "가", "body", "재시작 뒤에도 남을 본문", "version", 1), token, null, null); });
                var moved = executor.submit(() -> { moveGate.await(); return request(base, "PUT", "/api/notes/" + savedNoteId + "/topic",
                        Map.of("topicId", destination), token, null, null); });
                check(saved.get(10, TimeUnit.SECONDS), 200);
                check(moved.get(10, TimeUnit.SECONDS), 200);
            }
            var moved = json(get(base, "/api/notes/" + noteId, token));
            assertThat(moved.path("version").asInt()).isEqualTo(2);
            assertThat(moved.path("body").asString()).isEqualTo("재시작 뒤에도 남을 본문");
            assertThat(moved.path("topicId").asString()).isEqualTo(destination);

            check(request(base, "PUT", "/api/topics/order", Map.of("topicIds", List.of(destination, topic)), token, null, null), 200);
            var staleOrder = request(base, "PUT", "/api/topics/order", Map.of("topicIds", List.of(topic)), token, null, null);
            check(staleOrder, 409);
            assertThat(json(staleOrder).path("error").path("details").path("current").path("topics").size()).isEqualTo(2);
            check(request(base, "DELETE", "/api/notes/" + duplicate, null, token, null, null), 204);
            check(request(base, "DELETE", "/api/topics/" + destination, null, token, null, null), 204);
            var unassigned = json(get(base, "/api/notes/" + noteId, token));
            assertThat(unassigned.path("topicId").isNull()).isTrue();
            assertThat(unassigned.path("version").asInt()).isEqualTo(2);

            var denied = request(base, "POST", "/api/auth/refresh", null, null, cookie, "http://untrusted.example");
            check(denied, 403);
            assertThat(json(denied).path("error").path("code").asString()).isEqualTo("FORBIDDEN");
            assertThat(denied.headers().allValues("Set-Cookie")).isEmpty();
        }

        try (var context = start()) {
            String base = base(context);
            var refreshed = request(base, "POST", "/api/auth/refresh", null, null, cookie, APP_ORIGIN);
            check(refreshed, 200);
            assertThat(json(refreshed).path("user").path("id").asString()).isEqualTo(userId);
            String token = json(refreshed).path("accessToken").asString();
            String currentCookie = refreshCookie(refreshed);
            var persisted = json(get(base, "/api/notes/" + noteId, token));
            assertThat(persisted.path("body").asString()).isEqualTo("재시작 뒤에도 남을 본문");
            assertThat(persisted.path("version").asInt()).isEqualTo(2);
            assertThat(persisted.path("topicId").isNull()).isTrue();

            check(request(base, "POST", "/api/auth/logout", null, null, currentCookie, APP_ORIGIN), 204);
            check(request(base, "POST", "/api/auth/refresh", null, null, currentCookie, APP_ORIGIN), 401);
            var otherDevice = request(base, "POST", "/api/auth/refresh", null, null, otherDeviceCookie, APP_ORIGIN);
            check(otherDevice, 200);
            assertThat(json(otherDevice).path("user").path("id").asString()).isEqualTo(userId);
            check(request(base, "DELETE", "/api/notes/" + noteId, null, token, null, null), 204);
            check(request(base, "GET", "/api/notes/" + noteId, null, token, null, null), 404);
            check(request(base, "PUT", "/api/notes/" + noteId,
                    Map.of("title", "가", "body", "미저장 입력", "version", 2), token, null, null), 404);
        }
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(CapstoneApplication.class).profiles("test").run(
                "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--capstone.auth.google.enabled=false", "--capstone.auth.dev-token.enabled=true",
                "--capstone.auth.app-url=" + APP_ORIGIN);
    }

    private String base(ConfigurableApplicationContext context) {
        return "http://localhost:" + ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    private HttpResponse<String> request(String base, String method, String path, Object body,
            String token, String cookie, String origin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (cookie != null) request.header("Cookie", cookie);
        if (origin != null) request.header("Origin", origin);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String base, String path, String token) throws Exception {
        var response = request(base, "GET", path, null, token, null, null);
        check(response, 200);
        return response;
    }

    private String create(String base, String path, Map<String, ?> body, String token) throws Exception {
        var response = request(base, "POST", path, body, token, null, null);
        check(response, 201);
        return json(response).path("id").asString();
    }

    private String refreshCookie(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("CAPSTONE_REFRESH=")).findFirst().orElseThrow().split(";", 2)[0];
    }

    private JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }

    private void check(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).as(response.request().method() + " " + response.request().uri().getPath()).isEqualTo(expected);
    }

    private String encode(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }
}
