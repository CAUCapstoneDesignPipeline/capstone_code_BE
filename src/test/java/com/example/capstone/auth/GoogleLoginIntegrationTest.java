package com.example.capstone.auth;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import com.example.capstone.auth.config.AuthProperties;
import com.example.capstone.auth.service.GoogleOAuthClient;
import com.example.capstone.auth.service.IdentityLoginService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class GoogleLoginIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15");
    private static final RSAKey key;
    private static final HttpServer provider;
    private static final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private static final ObjectMapper json = new ObjectMapper();
    static {
        try {
            key = new RSAKeyGenerator(2048).keyID("fixture").generate();
            provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            provider.createContext("/jwks", exchange -> {
                byte[] bytes = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            provider.createContext("/token", exchange -> {
                try {
                    var form = query(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                    var grant = grants.remove(form.get("code"));
                    String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                            .digest(form.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
                    if (grant == null || !challenge.equals(grant.challenge()) || !"fixture-client".equals(form.get("client_id"))
                            || !"fixture-secret".equals(form.get("client_secret"))
                            || !"http://localhost:8080/api/auth/oauth2/google/callback".equals(form.get("redirect_uri"))) {
                        exchange.sendResponseHeaders(400,-1); exchange.close(); return;
                    }
                    String mode = grant.mode();
                    var claims = new JWTClaimsSet.Builder().issuer(mode.equals("issuer") ? "https://evil.invalid" : "https://accounts.google.com")
                            .audience(mode.equals("audience") ? "other-client" : "fixture-client").subject(grant.subject())
                            .issueTime(Date.from(Instant.now().minusSeconds(10)))
                            .expirationTime(Date.from(Instant.now().plusSeconds(mode.equals("expired") ? -1 : 300)))
                            .claim("nonce", mode.equals("nonce") ? "wrong" : grant.nonce())
                            .claim("email",mode.equals("notallowed") ? "outside@example.com" : "allowed@example.com")
                            .claim("email_verified",!mode.equals("unverified")).claim("name","테스트 사용자").build();
                    var signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("fixture").type(JOSEObjectType.JWT).build(),claims);
                    signed.sign(new RSASSASigner(mode.equals("signature") ? new RSAKeyGenerator(2048).generate() : key));
                    byte[] bytes = json.writeValueAsBytes(Map.of("id_token",signed.serialize()));
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
                } catch (Exception exception) { exchange.sendResponseHeaders(500,-1); exchange.close(); }
            });
            provider.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); provider.start();
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + provider.getAddress().getPort();
        registry.add("capstone.auth.google.enabled",() -> true);
        registry.add("capstone.auth.google.client-id",() -> "fixture-client");
        registry.add("capstone.auth.google.client-secret",() -> "fixture-secret");
        registry.add("capstone.auth.google.authorization-uri",() -> base + "/authorize");
        registry.add("capstone.auth.google.token-uri",() -> base + "/token");
        registry.add("capstone.auth.google.jwks-uri",() -> base + "/jwks");
        registry.add("capstone.auth.allowed-emails",() -> "allowed@example.com");
    }
    @AfterAll static void stop() { provider.stop(0); }
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private IdentityLoginService identities;
    @Autowired private AuthProperties properties;
    @Autowired private org.springframework.core.env.Environment environment;

    @Test
    void signsUpAndReauthenticatesWithBrowserBoundPkceAndRefreshOnlyInCookie() throws Exception {
        mvc.perform(get("/api/auth/providers")).andExpect(jsonPath("$.providers[0].id").value("google"));
        String subject = UUID.randomUUID().toString();
        var start = start("/notes?tag=한글");
        var first = finish(start,subject,"ok");
        assertThat(first.getResponse().getHeader("Location")).isEqualTo("http://localhost:5173/auth/callback?returnTo=%2Fnotes%3Ftag%3D%ED%95%9C%EA%B8%80");
        assertThat(first.getResponse().getHeader("Location")).doesNotContain("accessToken","refreshToken");
        assertThat(first.getResponse().getHeaders("Set-Cookie")).anySatisfy(cookie -> assertThat(cookie).contains("CAPSTONE_OAUTH=;","Max-Age=0","SameSite=Lax"));
        String refresh = first.getResponse().getHeaders("Set-Cookie").stream().filter(s -> s.startsWith("CAPSTONE_REFRESH=")).findFirst().orElseThrow().split(";",2)[0].split("=",2)[1];
        var token = mvc.perform(post("/api/auth/refresh").header("Origin","http://localhost:5173").cookie(new Cookie("CAPSTONE_REFRESH",refresh)))
                .andExpect(status().isOk()).andReturn();
        String id = json.readTree(token.getResponse().getContentAsString()).path("user").path("id").asString();
        finish(start,subject,"ok").getResponse();
        assertThat(callback(start,"unused",null).getResponse().getHeader("Location")).endsWith("error=oauth_failed");
        var again = finish(start("/"),subject,"ok");
        assertThat(again.getResponse().getHeader("Location")).contains("/auth/callback");
        assertThat(jdbc.queryForObject("select user_id::text from user_identity where provider='google' and provider_subject=?",String.class,subject)).isEqualTo(id);
    }

    @Test
    void rejectsProviderClaimsEmailsAndInvalidReturnPathsWithoutCreatingUsers() throws Exception {
        long before = jdbc.queryForObject("select count(*) from app_user",Long.class);
        for (String mode : List.of("nonce","issuer","audience","expired","signature","notallowed","unverified")) {
            var result = finish(start("/"), UUID.randomUUID().toString(), mode);
            String error = mode.equals("notallowed") ? "signup_not_allowed" : mode.equals("unverified") ? "email_not_verified" : "oauth_failed";
            assertThat(result.getResponse().getHeader("Location")).endsWith("error=" + error);
            assertThat(result.getResponse().getHeaders("Set-Cookie")).noneMatch(s -> s.startsWith("CAPSTONE_REFRESH="));
        }
        for (String value : List.of("https://evil.invalid","//evil.invalid","/%252f%252fevil","/\\evil","/%0a","/%", "", "/"+"a".repeat(500))) {
            mvc.perform(get("/api/auth/oauth2/google").param("returnTo",value)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED")).andExpect(header().doesNotExist("Set-Cookie"));
        }
        assertThat(jdbc.queryForObject("select count(*) from app_user",Long.class)).isEqualTo(before);
        // Enabling an incomplete configuration must fail, rather than advertise unusable login.
        var incomplete = new AuthProperties(properties.appUrl(),properties.jwt(),
                new AuthProperties.Google(true,"","",properties.google().redirectUri(),properties.google().authorizationUri(),properties.google().tokenUri(),properties.google().jwksUri()),"");
        assertThatThrownBy(() -> new GoogleOAuthClient(incomplete,json,environment,java.time.Clock.systemUTC())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMissingMismatchedExpiredAndReusedStateAndMapsCancellation() throws Exception {
        var s = start("/");
        assertThat(mvc.perform(get("/api/auth/oauth2/google/callback").param("state",s.query().get("state")).param("code","unused"))
                .andReturn().getResponse().getHeader("Location")).endsWith("oauth_failed");
        assertThat(callback(new Started(Map.of("state","wrong"),s.cookie()),"unused",null).getResponse().getHeader("Location")).endsWith("oauth_failed");
        jdbc.update("update oauth_attempt set expires_at = now() - interval '1 minute' where state_hash=?",com.example.capstone.auth.service.RefreshTokenService.hash(s.query().get("state")));
        assertThat(callback(s,"unused",null).getResponse().getHeader("Location")).endsWith("oauth_failed");
        var cancel = start("/");
        assertThat(callback(cancel,null,"access_denied").getResponse().getHeader("Location")).endsWith("access_denied");
        assertThat(callback(cancel,null,"access_denied").getResponse().getHeader("Location")).endsWith("oauth_failed");
    }

    @Test
    void concurrentFirstLoginKeepsOneUserAndRollsBackLosingCreation() throws Exception {
        String subject = UUID.randomUUID().toString();
        long before = jdbc.queryForObject("select count(*) from app_user",Long.class);
        var first = start("/"); var second = start("/");
        CyclicBarrier gate = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> { gate.await(); return finish(first,subject,"ok"); });
            var b = executor.submit(() -> { gate.await(); return finish(second,subject,"ok"); });
            assertThat(a.get(15,TimeUnit.SECONDS).getResponse().getHeader("Location")).contains("/auth/callback");
            assertThat(b.get(15,TimeUnit.SECONDS).getResponse().getHeader("Location")).contains("/auth/callback");
        }
        assertThat(jdbc.queryForObject("select count(*) from app_user",Long.class)).isEqualTo(before+1);
        assertThat(jdbc.queryForObject("select count(*) from user_identity where provider_subject=?",Long.class,subject)).isEqualTo(1);
    }
    private Started start(String path) throws Exception {
        var r=mvc.perform(get("/api/auth/oauth2/google").param("returnTo",path)).andExpect(status().isFound())
                .andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.containsString("SameSite=Lax"))).andReturn().getResponse();
        return new Started(query(URI.create(r.getHeader("Location")).getRawQuery()),r.getHeader("Set-Cookie").split(";",2)[0].split("=",2)[1]);
    }
    private MvcResult finish(Started start,String subject,String mode) throws Exception {
        String code=UUID.randomUUID().toString();
        grants.put(code,new Grant(subject,mode,start.query().get("nonce"),start.query().get("code_challenge")));
        return callback(start,code,null);
    }
    private MvcResult callback(Started start,String code,String error) throws Exception {
        var request=get("/api/auth/oauth2/google/callback").param("state",start.query().get("state")).cookie(new Cookie("CAPSTONE_OAUTH",start.cookie()));
        if(code!=null) request.param("code",code); if(error!=null) request.param("error",error);
        return mvc.perform(request).andExpect(status().isFound()).andReturn();
    }
    private static Map<String,String> query(String value) {
        Map<String,String> result=new HashMap<>();
        for(String item:value.split("&")) { var parts=item.split("=",2); result.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),URLDecoder.decode(parts[1],StandardCharsets.UTF_8)); }
        return result;
    }
    private record Started(Map<String,String> query,String cookie) { }
    private record Grant(String subject,String mode,String nonce,String challenge) { }
}
