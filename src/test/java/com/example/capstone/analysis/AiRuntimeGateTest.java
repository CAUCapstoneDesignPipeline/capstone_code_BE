package com.example.capstone.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;
import com.example.capstone.analysis.client.*;
import com.example.capstone.analysis.config.*;
import com.example.capstone.analysis.support.SyntheticPipelineServer;
import static org.junit.jupiter.api.Assertions.*;

class AiRuntimeGateTest {
    @TempDir Path temporary;
    final ObjectMapper mapper=new ObjectMapper();
    final AiSchemaValidator schemas=new AiSchemaValidator(mapper);
    final AiResponseValidator validator=new AiResponseValidator(schemas);
    MockEnvironment environment(String... profiles) { var env=new MockEnvironment();env.setActiveProfiles(profiles);return env; }
    AiClientProperties client(String url,String token,String ca) { return new AiClientProperties(url,token,ca,Duration.ofSeconds(3),Duration.ofSeconds(130),Duration.ofSeconds(40),3,2,Duration.ofSeconds(2)); }
    @Test void productionRequiresExplicitReleaseAndCannotUseMixedProfileBypasses() {
        var flags=new AiProperties(true);var ordinary=new AiPipelineProperties(null,null,null,false,Map.of());var synthetic=new AiPipelineProperties(null,null,null,true,Map.of());
        assertFalse(new AiFeatureGate(flags,new AiRuntimeProperties(false,null),ordinary,environment("prod")).allowed());
        assertTrue(new AiFeatureGate(flags,new AiRuntimeProperties(true,null),ordinary,environment("prod")).allowed());
        assertFalse(new AiFeatureGate(flags,new AiRuntimeProperties(false,null),ordinary,environment("prod","test")).allowed());
        assertFalse(new AiFeatureGate(flags,new AiRuntimeProperties(true,null),synthetic,environment("prod","test")).allowed());
        assertThrows(AiCallException.class,()->new AiHttpClient(client("http://localhost:12345","synthetic-service-token",null),mapper,schemas,validator,environment("prod","test")));
    }
    @Test void injectedClientFailsClosedWithoutPreventingOptionalFeatureStartup() {
        for(var properties:java.util.List.of(client("not a URI","synthetic-service-token",null),client("https://ai.internal","short",null),client("https://ai.internal","synthetic-service-token",temporary.resolve("missing-ca").toString()))) {
            assertFalse(new AiHttpClient(new AiProperties(true),properties,mapper,schemas,validator,environment("prod")).configured());
            assertFalse(new AiHttpClient(new AiProperties(false),properties,mapper,schemas,validator,environment("prod")).configured());
        }
    }
    @Test void privateCaTransportChecksActualTlsTrustAndHostnameInProductionProfile() throws Exception {
        Path store=temporary.resolve("fixture.p12"),ca=temporary.resolve("fixture-ca.pem");String password="ephemeral-synthetic-only";
        Process keytool=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-dname","CN=localhost","-ext","SAN=dns:localhost","-validity","2","-keystore",store.toString(),"-storetype","PKCS12","-storepass",password,"-keypass",password,"-noprompt").redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if(!keytool.waitFor(20,java.util.concurrent.TimeUnit.SECONDS)) { keytool.destroyForcibly();fail("합성 인증서 생성 시간 초과"); }assertEquals(0,keytool.exitValue());
        var keys=java.security.KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(store)) { keys.load(input,password.toCharArray()); }
        Files.writeString(ca,"-----BEGIN CERTIFICATE-----\n"+java.util.Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(keys.getCertificate("fixture").getEncoded())+"\n-----END CERTIFICATE-----\n");
        var managers=javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());managers.init(keys,password.toCharArray());var tls=javax.net.ssl.SSLContext.getInstance("TLS");tls.init(managers.getKeyManagers(),null,null);
        try(var server=new com.example.capstone.analysis.support.ScriptedAiServer("synthetic-service-token",2097152,tls)) {
            server.enqueue(new com.example.capstone.analysis.support.ScriptedAiServer.Reply(200,"{\"status\":\"ready\",\"schemaVersion\":\"1.0\",\"profileVersions\":[\"fixture\"]}",Map.of()));
            var trusted=new AiHttpClient(new AiProperties(true),client(server.baseUri().toString(),"synthetic-service-token",ca.toString()),mapper,schemas,validator,environment("prod"));assertTrue(trusted.configured());trusted.ready(java.util.Set.of("fixture"));
            var wrongHost=new AiHttpClient(client(server.baseUri().toString().replace("localhost","127.0.0.1"),"synthetic-service-token",ca.toString()),mapper,schemas,validator,environment("prod"));
            assertEquals(AiCallException.Reason.AUTHENTICATION,assertThrows(AiCallException.class,()->wrongHost.ready(java.util.Set.of("fixture"))).reason());
        }
    }
    @Test void pinFileIntegrityProfileIdfModelAndCaBindingsFailClosed() throws Exception {
        Path policy=temporary.resolve("policy.json"),ca=temporary.resolve("ca.pem"),proof=temporary.resolve("attestation.json");Files.writeString(policy,SyntheticPipelineServer.POLICY);Files.writeString(ca,"synthetic-ca-fingerprint-input");
        var defaults=new AiPipelineConfiguration(new AiProperties(true),new AiPipelineProperties(policy.toString(),null,null,true,Map.of()),client(null,null,null),mapper,schemas,environment("test"));
        var attestation=mapper.createObjectNode();var release=attestation.putObject("releaseManifest").put("kind","ai").put("image","example.invalid/ai@sha256:"+"1".repeat(64)).put("sourceSha","2".repeat(40));
        release.putObject("idf").put("sha256","3".repeat(64));release.putObject("model").put("profile","embeddings-bge-m3-1").put("name","BAAI/bge-m3").put("revision","5617a9f61b028005a4858fdac845db406aefb181");release.set("profiles",mapper.valueToTree(defaults.requiredProfiles()));release.putObject("tls").put("caSha256",AiResponseValidator.hash(Files.readString(ca)));
        var expected=attestation.putObject("expectedMeta");for(String profile:defaults.requiredProfiles()) expected.putObject(profile).put("profileVersion",profile).put("provider","fixture").put("model","fixture-model").put("modelRevision","fixture-revision").putNull("promptVersion").put("schemaVersion","1.0").put("preprocessingVersion","fixture-preprocessing");
        ((tools.jackson.databind.node.ObjectNode)expected.path("embeddings-bge-m3-1")).put("model","BAAI/bge-m3").put("modelRevision","5617a9f61b028005a4858fdac845db406aefb181");
        Files.writeString(proof,attestation.toString());String hash=AiResponseValidator.hash(Files.readString(proof));
        var good=new AiPipelineConfiguration(new AiProperties(true),new AiPipelineProperties(policy.toString(),proof.toString(),hash,false,Map.of()),client("https://ai.internal","synthetic-service-token",ca.toString()),mapper,schemas,environment("prod"));assertTrue(good.configured());
        good.verify(AiOperation.EXTRACT,expected.path("extract-det-3"));var mismatch=expected.path("extract-det-3").deepCopy();((tools.jackson.databind.node.ObjectNode)mismatch).put("modelRevision","different");assertThrows(AiCallException.class,()->good.verify(AiOperation.EXTRACT,mismatch));assertFalse(good.configured());
        for(String change:java.util.List.of("file","idf","model","ca","profile")) {
            var changed=attestation.deepCopy();var r=(tools.jackson.databind.node.ObjectNode)changed.path("releaseManifest");
            if(change.equals("idf"))((tools.jackson.databind.node.ObjectNode)r.path("idf")).put("sha256","invalid");
            if(change.equals("model"))((tools.jackson.databind.node.ObjectNode)r.path("model")).put("revision","wrong");
            if(change.equals("ca"))((tools.jackson.databind.node.ObjectNode)r.path("tls")).put("caSha256","0".repeat(64));
            if(change.equals("profile"))r.putArray("profiles").add("extract-det-3");
            Files.writeString(proof,changed.toString());String pin=change.equals("file")?"0".repeat(64):AiResponseValidator.hash(Files.readString(proof));
            assertFalse(new AiPipelineConfiguration(new AiProperties(true),new AiPipelineProperties(policy.toString(),proof.toString(),pin,false,Map.of()),client("https://ai.internal","synthetic-service-token",ca.toString()),mapper,schemas,environment("prod")).configured(),change);
        }
    }
}
