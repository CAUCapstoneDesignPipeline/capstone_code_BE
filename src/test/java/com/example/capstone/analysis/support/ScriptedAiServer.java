package com.example.capstone.analysis.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * Test-only transport double. It deliberately defines no AI operation, DTO, retry policy or profile.
 * Supply synthetic input only. Production AI clients and workers are not implemented by this helper.
 */
public final class ScriptedAiServer implements AutoCloseable {
    private static final int QUEUE_CAPACITY = 32;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final BlockingQueue<Reply> replies = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final BlockingQueue<RecordedRequest> requests = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final String authorization;
    private final int maxRequestBytes;
    private final String scheme;

    public ScriptedAiServer(String testToken, int maxRequestBytes) throws IOException {
        this(testToken, maxRequestBytes, null);
    }

    public ScriptedAiServer(String testToken, int maxRequestBytes, SSLContext tls) throws IOException {
        if (testToken == null || testToken.isBlank() || maxRequestBytes < 1 || maxRequestBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("A synthetic token and a bounded request size are required");
        }
        authorization = "Bearer " + testToken;
        this.maxRequestBytes = maxRequestBytes;
        var address = new InetSocketAddress("127.0.0.1", 0);
        if (tls == null) {
            scheme = "http";
            server = HttpServer.create(address, 0);
        } else {
            scheme = "https";
            HttpsServer https = HttpsServer.create(address, 0);
            https.setHttpsConfigurator(new HttpsConfigurator(tls));
            server = https;
        }
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    public URI baseUri() {
        // The generated test certificate has a localhost SAN, rather than an IP SAN.
        return URI.create(scheme + "://localhost:" + server.getAddress().getPort());
    }

    public void enqueue(Reply reply) {
        if (!replies.offer(Objects.requireNonNull(reply))) {
            throw new IllegalStateException("Fixture response queue is full");
        }
    }

    public RecordedRequest takeRequest(Duration timeout) throws InterruptedException {
        RecordedRequest request = requests.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (request == null) {
            throw new IllegalStateException("No fixture request arrived within the test deadline");
        }
        return request;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String presented = exchange.getRequestHeaders().getFirst("Authorization");
            if (presented == null || !MessageDigest.isEqual(authorization.getBytes(StandardCharsets.UTF_8),
                    presented.getBytes(StandardCharsets.UTF_8))) {
                send(exchange, 401, "{\"error\":\"fixture_authentication_failed\"}", Map.of());
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(maxRequestBytes + 1);
            if (body.length > maxRequestBytes) {
                send(exchange, 413, "{\"error\":\"fixture_request_too_large\"}", Map.of());
                return;
            }
            // Do not retain Authorization, cookies, or arbitrary caller-supplied headers.
            var request = new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-Request-Id"),
                    exchange.getRequestHeaders().getFirst("X-Attempt-Id"), body);
            if (!requests.offer(request)) {
                send(exchange, 503, "{\"error\":\"fixture_capture_queue_full\"}", Map.of());
                return;
            }
            Reply reply = replies.poll();
            if (reply == null) {
                send(exchange, 501, "{\"error\":\"fixture_response_not_scripted\"}", Map.of());
                return;
            }
            try {
                if (!reply.release.await(10, TimeUnit.SECONDS)) {
                    send(exchange, 504, "{\"error\":\"fixture_gate_deadline\"}", Map.of());
                    return;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            send(exchange, reply.status, reply.body, reply.headers);
        }
    }

    private static void send(HttpExchange exchange, int status, String body, Map<String, String> headers)
            throws IOException {
        byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        exchange.sendResponseHeaders(status, encoded.length);
        exchange.getResponseBody().write(encoded);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        replies.clear();
        requests.clear();
    }

    public static final class Reply {
        private final int status;
        private final String body;
        private final Map<String, String> headers;
        private final CountDownLatch release;

        public Reply(int status, String body, Map<String, String> headers) {
            this(status, body, headers, new CountDownLatch(0));
        }

        private Reply(int status, String body, Map<String, String> headers, CountDownLatch release) {
            if (status < 200 || status > 599 || status == 204 || status == 304) {
                throw new IllegalArgumentException("Fixture response must permit a response body");
            }
            this.status = status;
            this.body = Objects.requireNonNull(body);
            this.headers = Map.copyOf(headers);
            this.release = release;
        }

        public static Reply gated(int status, String body) {
            return new Reply(status, body, Map.of(), new CountDownLatch(1));
        }

        public void release() {
            release.countDown();
        }

        @Override
        public String toString() {
            return "Reply[status=" + status + ", body=<redacted>, headers=<redacted>]";
        }
    }

    public static final class RecordedRequest {
        private final String method;
        private final String path;
        private final String requestId;
        private final String attemptId;
        private final byte[] body;

        private RecordedRequest(String method, String path, String requestId, String attemptId, byte[] body) {
            this.method = method;
            this.path = path;
            this.requestId = requestId;
            this.attemptId = attemptId;
            this.body = body.clone();
        }

        public String method() { return method; }
        public String path() { return path; }
        public String requestId() { return requestId; }
        public String attemptId() { return attemptId; }
        public byte[] body() { return body.clone(); }

        @Override
        public String toString() {
            return "RecordedRequest[method=" + method + ", path=<redacted>, ids=<redacted>, body=<redacted>]";
        }
    }
}
