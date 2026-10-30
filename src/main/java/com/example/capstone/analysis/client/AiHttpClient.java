package com.example.capstone.analysis.client;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.example.capstone.analysis.config.AiClientProperties;

/** The sole transport retry layer. Every attempt reuses serialized bytes and the logical stage ID. */
@Component
public final class AiHttpClient {
    public static final int REQUEST_LIMIT = 2 * 1024 * 1024;
    public static final int RESPONSE_LIMIT = 4 * 1024 * 1024;
    private final AiClientProperties config;
    private final ObjectMapper mapper;
    private final AiSchemaValidator schemas;
    private final AiResponseValidator validator;
    private final HttpClient http;
    private final URI base;
    private final Semaphore permits;
    public AiHttpClient(AiClientProperties config, ObjectMapper mapper, AiSchemaValidator schemas,
            AiResponseValidator validator, Environment environment) {
        this.config = config; this.mapper = mapper; this.schemas = schemas; this.validator = validator;
        permits = new Semaphore(config.maxConcurrent());
        if (config.baseUrl() == null || config.baseUrl().isBlank()) { base = null; http = null; return; }
        URI uri;
        try { uri = URI.create(config.baseUrl()); } catch (RuntimeException invalid) { throw configuration(); }
        boolean test = java.util.Arrays.asList(environment.getActiveProfiles()).contains("test");
        boolean loopback = test && "http".equals(uri.getScheme()) && Set.of("127.0.0.1", "localhost").contains(uri.getHost());
        if ((!"https".equals(uri.getScheme()) && !loopback) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || !Set.of("", "/").contains(uri.getPath())
                || config.serviceToken() == null || config.serviceToken().length() < 16
                || config.serviceToken().chars().anyMatch(c -> c < 33 || c > 126)) throw configuration();
        base = URI.create(uri.toString().replaceAll("/$", "") + "/internal/v1/");
        var builder = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER);
        if ("https".equals(uri.getScheme())) {
            if (config.caCertificate() == null || config.caCertificate().isBlank()) throw configuration();
            try (var input = Files.newInputStream(Path.of(config.caCertificate()))) {
                var certificates = CertificateFactory.getInstance("X.509").generateCertificates(input);
                if (certificates.isEmpty()) throw configuration();
                var trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null);
                int i = 0; for (var certificate : certificates) trust.setCertificateEntry("ca-" + i++, certificate);
                var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); factory.init(trust);
                var ssl = SSLContext.getInstance("TLS"); ssl.init(null, factory.getTrustManagers(), null); builder.sslContext(ssl);
            } catch (Exception invalid) { throw configuration(); }
        }
        http = builder.build();
    }
    public boolean configured() { return http != null; }
    public void ready(Set<String> requiredProfiles) {
        var response = exchange("health/ready", null, UUID.randomUUID().toString(), config.callTimeout(), Instant.now().plusSeconds(130));
        schemas.validate("Readiness", response.body());
        Set<String> actual = new HashSet<>(); response.body().path("profileVersions").forEach(n -> actual.add(n.asString()));
        if (!actual.containsAll(requiredProfiles)) throw new AiCallException(AiCallException.Reason.PROFILE);
    }
    public CallResult call(AiOperation operation, JsonNode payload, Instant deadline) {
        schemas.validate(operation.requestSchema(), payload);
        validator.validateRequest(payload);
        byte[] body = mapper.writeValueAsBytes(payload);
        if (body.length > REQUEST_LIMIT) throw new AiCallException(AiCallException.Reason.INPUT_LIMIT);
        String requestId = payload.path("context").path("stageExecutionId").asString();
        try { UUID.fromString(requestId); } catch (RuntimeException invalid) { throw new AiCallException(AiCallException.Reason.INVALID_RESPONSE); }
        // JDK HTTP has one whole-response future. Enforce the read ceiling conservatively
        // from dispatch, so connect/header/body together cannot exceed 125s (embedding 35s).
        Duration callLimit=operation == AiOperation.EMBEDDINGS ? config.embeddingTimeout() : config.callTimeout();
        Duration readCeiling=Duration.ofSeconds(operation == AiOperation.EMBEDDINGS ? 35 : 125);
        var result = exchange(operation.path(), body, requestId,
                callLimit.compareTo(readCeiling)<0?callLimit:readCeiling, deadline);
        validator.validate(operation, payload, result.body());
        return result;
    }
    private CallResult exchange(String path, byte[] body, String requestId, Duration timeout, Instant deadline) {
        if (!configured()) throw configuration();
        if (!permits.tryAcquire()) throw new AiCallException(AiCallException.Reason.UNAVAILABLE);
        long started = System.nanoTime();
        try {
            AiCallException last = new AiCallException(AiCallException.Reason.UNAVAILABLE);
            for (int attempt = 1; attempt <= config.maxAttempts(); attempt++) {
                Duration remaining = Duration.between(Instant.now(), deadline);
                if (remaining.isNegative() || remaining.isZero()) throw new AiCallException(AiCallException.Reason.TIMEOUT);
                Duration limit = remaining.compareTo(timeout) < 0 ? remaining : timeout;
                var request = HttpRequest.newBuilder(base.resolve(path)).timeout(limit)
                        .header("Authorization", "Bearer " + config.serviceToken()).header("X-Request-Id", requestId)
                        .header("X-Attempt-Id", UUID.randomUUID().toString()).header("Idempotency-Key", requestId)
                        .header("Accept", "application/json");
                if (body == null) request.GET();
                else request.header("Content-Type", "application/json").header("X-Input-SHA256", hashBytes(body))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
                long delay = Math.min(4000, config.retryBase().toMillis() * (1L << (attempt - 1)));
                CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request.build(), ignored -> new BoundedBody(RESPONSE_LIMIT));
                try {
                    var response = future.get(Math.max(1, limit.toMillis()), TimeUnit.MILLISECONDS);
                    int status = response.statusCode();
                    if (status == 200) {
                        if (!response.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
                            throw new AiCallException(AiCallException.Reason.INVALID_RESPONSE);
                        }
                        try { return new CallResult(mapper.readTree(response.body()), attempt, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)); }
                        catch (RuntimeException malformed) { throw new AiCallException(AiCallException.Reason.INVALID_RESPONSE); }
                    }
                    if (status == 401 || status == 403) throw new AiCallException(AiCallException.Reason.AUTHENTICATION);
                    if (status != 429 && status != 503) throw new AiCallException(AiCallException.Reason.INVALID_RESPONSE);
                    last = new AiCallException(AiCallException.Reason.UNAVAILABLE);
                    try {
                        long retry = Long.parseLong(response.headers().firstValue("Retry-After").orElse("0"));
                        if (retry >= 1 && retry <= 60) delay = retry * 1000;
                    } catch (NumberFormatException ignored) { /* Use bounded local backoff. */ }
                } catch (java.util.concurrent.TimeoutException timeoutException) {
                    future.cancel(true); last = new AiCallException(AiCallException.Reason.TIMEOUT);
                } catch (java.util.concurrent.ExecutionException failed) {
                    future.cancel(true);
                    Throwable cause = failed.getCause();
                    if (cause instanceof AiCallException bounded) throw bounded;
                    if (cause instanceof javax.net.ssl.SSLException) throw new AiCallException(AiCallException.Reason.AUTHENTICATION);
                    last = new AiCallException(AiCallException.Reason.UNAVAILABLE);
                } catch (InterruptedException interrupted) {
                    future.cancel(true); Thread.currentThread().interrupt(); throw new AiCallException(AiCallException.Reason.UNAVAILABLE);
                }
                if (attempt == config.maxAttempts()) throw last;
                delay += delay == 0 ? 0 : ThreadLocalRandom.current().nextLong(Math.min(250, delay) + 1);
                if (!Instant.now().plusMillis(delay).isBefore(deadline)) throw new AiCallException(AiCallException.Reason.TIMEOUT);
                try { Thread.sleep(delay); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new AiCallException(AiCallException.Reason.UNAVAILABLE);
                }
            }
            throw last;
        } finally { permits.release(); }
    }
    private static String hashBytes(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static AiCallException configuration() { return new AiCallException(AiCallException.Reason.CONFIGURATION); }
    public record CallResult(JsonNode body, int attempts, long durationMs) {
        @Override public String toString() { return "AiCallResult[attempts=" + attempts + ", durationMs=" + durationMs + ", body=<redacted>]"; }
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final int max;
        private Flow.Subscription subscription;
        BoundedBody(int max) { this.max = max; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> items) {
            for (var item : items) {
                if (body.size() + item.remaining() > max) {
                    subscription.cancel(); result.completeExceptionally(new AiCallException(AiCallException.Reason.INVALID_RESPONSE)); return;
                }
                byte[] bytes = new byte[item.remaining()]; item.get(bytes); body.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(body.toByteArray()); }
    }
}
