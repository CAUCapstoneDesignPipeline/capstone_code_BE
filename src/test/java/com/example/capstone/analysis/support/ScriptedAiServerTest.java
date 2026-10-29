package com.example.capstone.analysis.support;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScriptedAiServerTest {
    private static final String TEST_TOKEN = "synthetic-test-token-only";
    private static final String PAYLOAD = "{\"synthetic\":\"근거 🧠\"}";
    private static SSLContext serverTls;
    private static SSLContext trustedClientTls;
    @TempDir static Path temporary;

    @BeforeAll
    static void createEphemeralLocalCertificate() throws Exception {
        Path store = temporary.resolve("fixture.p12");
        String password = "ephemeral-test-store";
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2",
                "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", password,
                "-keypass", password, "-noprompt")
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        boolean finished = keytool.waitFor(20, TimeUnit.SECONDS);
        if (!finished) keytool.destroyForcibly();
        assertThat(finished).as("test key generation completed").isTrue();
        assertThat(keytool.exitValue()).as("test key generation exit status").isZero();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keys.load(input, password.toCharArray()); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, password.toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(keys);
        serverTls = SSLContext.getInstance("TLS");
        serverTls.init(managers.getKeyManagers(), null, null);
        trustedClientTls = SSLContext.getInstance("TLS");
        trustedClientTls.init(null, trust.getTrustManagers(), null);
    }

    @Test
    void preservesExactRequestBytesAndIdsAcrossScriptedFailures() throws Exception {
        try (var server = new ScriptedAiServer(TEST_TOKEN, 1024); var client = HttpClient.newHttpClient()) {
            server.enqueue(new ScriptedAiServer.Reply(429, "{\"error\":\"rate_limit_fixture\"}", Map.of("Retry-After", "1")));
            server.enqueue(new ScriptedAiServer.Reply(503, "{\"error\":\"unavailable_fixture\"}", Map.of()));
            server.enqueue(new ScriptedAiServer.Reply(200, "{\"profile\":\"fixture-only\"}", Map.of()));
            // This explicitly sends three requests; it does not claim to implement BE retry behavior.
            for (int attempt = 1; attempt <= 3; attempt++) {
                var response = client.send(request(server, TEST_TOKEN, PAYLOAD, attempt), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(new int[]{429, 503, 200}[attempt - 1]);
                if (attempt == 1) assertThat(response.headers().firstValue("Retry-After")).contains("1");
                var captured = server.takeRequest(Duration.ofSeconds(2));
                assertThat(captured.requestId()).isEqualTo("synthetic-fixed-request");
                assertThat(captured.attemptId()).isEqualTo("attempt-" + attempt);
                assertThat(captured.body()).isEqualTo(PAYLOAD.getBytes(StandardCharsets.UTF_8));
                assertThat(captured.method()).isEqualTo("POST");
                assertThat(captured.path()).isEqualTo("/internal/v1/fixture");
                byte[] mutableCopy = captured.body();
                mutableCopy[0] = 0;
                assertThat(captured.body()[0]).isEqualTo((byte) '{');
                assertThat(captured.toString()).doesNotContain(TEST_TOKEN, "근거", "synthetic-fixed-request", "/internal");
            }
            assertThat(client.send(request(server, TEST_TOKEN, PAYLOAD, 4), HttpResponse.BodyHandlers.ofString())
                    .statusCode()).isEqualTo(501);
        }
    }

    @Test
    void rejectsWrongTokensAndOversizeWithoutConsumingScriptedSuccess() throws Exception {
        try (var server = new ScriptedAiServer(TEST_TOKEN, 8); var client = HttpClient.newHttpClient()) {
            server.enqueue(new ScriptedAiServer.Reply(200, "{}", Map.of()));
            assertThat(client.send(request(server, "wrong-synthetic-token", "{}", 1), HttpResponse.BodyHandlers.ofString())
                    .statusCode()).isEqualTo(401);
            assertThat(client.send(request(server, TEST_TOKEN, "🧠🧠a", 2), HttpResponse.BodyHandlers.ofString())
                    .statusCode()).isEqualTo(413);
            assertThat(client.send(request(server, TEST_TOKEN, "🧠🧠", 3), HttpResponse.BodyHandlers.ofString())
                    .statusCode()).isEqualTo(200);
            assertThat(server.takeRequest(Duration.ofSeconds(2)).attemptId()).isEqualTo("attempt-3");
        }
    }

    @Test
    void allowsMalformedOrUnsupportedPayloadsWithoutPretendingToValidateTheirContract() throws Exception {
        try (var server = new ScriptedAiServer(TEST_TOKEN, 1024); var client = HttpClient.newHttpClient()) {
            server.enqueue(new ScriptedAiServer.Reply(200, "not-json", Map.of()));
            server.enqueue(new ScriptedAiServer.Reply(200, "{\"profile\":\"unsupported-fixture\"}", Map.of()));
            assertThat(client.send(request(server, TEST_TOKEN, "{}", 1), HttpResponse.BodyHandlers.ofString()).body())
                    .isEqualTo("not-json");
            assertThat(client.send(request(server, TEST_TOKEN, "{}", 2), HttpResponse.BodyHandlers.ofString()).body())
                    .contains("unsupported-fixture");
        }
    }

    @Test
    void synchronizesLateResponseAndTimeoutWithoutSleepBasedRaces() throws Exception {
        try (var server = new ScriptedAiServer(TEST_TOKEN, 1024); var client = HttpClient.newHttpClient()) {
            var late = ScriptedAiServer.Reply.gated(200, "{\"synthetic\":true}");
            server.enqueue(late);
            var future = client.sendAsync(request(server, TEST_TOKEN, "{}", 1), HttpResponse.BodyHandlers.ofString());
            server.takeRequest(Duration.ofSeconds(2));
            assertThat(future.isDone()).isFalse();
            late.release();
            assertThat(future.get(3, TimeUnit.SECONDS).statusCode()).isEqualTo(200);

            var blocked = ScriptedAiServer.Reply.gated(200, "{}");
            server.enqueue(blocked);
            var timeout = client.sendAsync(HttpRequest.newBuilder(server.baseUri().resolve("/internal/v1/fixture"))
                    .timeout(Duration.ofSeconds(1)).header("Authorization", "Bearer " + TEST_TOKEN)
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            server.takeRequest(Duration.ofSeconds(2));
            try {
                assertThatThrownBy(() -> timeout.get(3, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(HttpTimeoutException.class);
            } finally {
                blocked.release();
            }
        }
    }

    @Test
    void verifiesTrustAndHostnameWithoutDisablingTlsValidation() throws Exception {
        try (var server = new ScriptedAiServer(TEST_TOKEN, 1024, serverTls);
                var trusted = HttpClient.newBuilder().sslContext(trustedClientTls).build();
                var untrusted = HttpClient.newHttpClient()) {
            server.enqueue(new ScriptedAiServer.Reply(200, "{}", Map.of()));
            assertThatThrownBy(() -> untrusted.send(request(server, TEST_TOKEN, "{}", 1), HttpResponse.BodyHandlers.ofString()))
                    .isInstanceOf(SSLHandshakeException.class);
            var wrongHostname = HttpRequest.newBuilder(java.net.URI.create(server.baseUri().toString().replace("localhost", "127.0.0.1")))
                    .timeout(Duration.ofSeconds(3)).header("Authorization", "Bearer " + TEST_TOKEN).GET().build();
            assertThatThrownBy(() -> trusted.send(wrongHostname, HttpResponse.BodyHandlers.ofString()))
                    .isInstanceOf(SSLHandshakeException.class);
            assertThat(trusted.send(request(server, TEST_TOKEN, "{}", 2), HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(200);
        }
    }

    private static HttpRequest request(ScriptedAiServer server, String token, String body, int attempt) {
        return HttpRequest.newBuilder(server.baseUri().resolve("/internal/v1/fixture")).timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + token).header("X-Request-Id", "synthetic-fixed-request")
                .header("X-Attempt-Id", "attempt-" + attempt).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    }
}
