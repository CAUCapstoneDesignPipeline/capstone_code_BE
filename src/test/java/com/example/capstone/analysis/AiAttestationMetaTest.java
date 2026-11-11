package com.example.capstone.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.example.capstone.analysis.client.AiCallException;
import com.example.capstone.analysis.client.AiOperation;
import com.example.capstone.analysis.client.AiResponseValidator;
import com.example.capstone.analysis.client.AiSchemaValidator;
import com.example.capstone.analysis.config.AiClientProperties;
import com.example.capstone.analysis.config.AiPipelineConfiguration;
import com.example.capstone.analysis.config.AiPipelineProperties;
import com.example.capstone.analysis.config.AiProperties;
import com.example.capstone.analysis.support.SyntheticPipelineServer;
import static org.junit.jupiter.api.Assertions.*;

/** attestation expectedMeta의 stamp 하나·stamp 배열 대조 (AI 404dabf derive-sound-1은 판정 유무에 따라 meta가 둘이다). */
class AiAttestationMetaTest {
    @TempDir Path temporary;
    final ObjectMapper mapper=new ObjectMapper();
    final AiSchemaValidator schemas=new AiSchemaValidator(mapper);

    @Test void defaultProfilesTargetAi404dabf() throws Exception {
        var configuration=configuration(attestation(true));
        assertEquals("facts-llm-3",configuration.profile(AiOperation.FACTS));assertEquals("derive-sound-1",configuration.profile(AiOperation.DERIVE));
        assertEquals(SyntheticPipelineServer.ATTESTED_META.keySet(),configuration.requiredProfiles());
    }
    @Test void stampArrayAcceptsEitherDeriveSoundMetaAndRejectsOthers() throws Exception {
        var configuration=configuration(attestation(true));assertTrue(configuration.configured());
        configuration.verify(AiOperation.DERIVE,fixtureMeta("derive-sound-judged"));
        configuration.verify(AiOperation.DERIVE,fixtureMeta("derive-sound-unjudged"));
        configuration.verify(AiOperation.FACTS,fixtureMeta("facts"));
        assertTrue(configuration.configured());
        var other=(ObjectNode)fixtureMeta("derive-sound-judged");other.put("model","claude-sonnet-5-5").put("modelRevision","claude-sonnet-5-5@92984930785c");
        assertEquals(AiCallException.Reason.PROFILE,assertThrows(AiCallException.class,()->configuration.verify(AiOperation.DERIVE,other)).reason());
        assertFalse(configuration.configured());assertTrue(configuration.quarantined());
    }
    @Test void singleStampFormStillMatchesExactlyAndQuarantinesTheOtherDeriveShape() throws Exception {
        var configuration=configuration(attestation(false));assertTrue(configuration.configured());
        configuration.verify(AiOperation.DERIVE,fixtureMeta("derive-sound-judged"));configuration.verify(AiOperation.FACTS,fixtureMeta("facts"));
        assertThrows(AiCallException.class,()->configuration.verify(AiOperation.DERIVE,fixtureMeta("derive-sound-unjudged")));
        assertTrue(configuration.quarantined());
    }
    @Test void malformedStampArraysFailClosedAtStartup() throws Exception {
        List<Consumer<ObjectNode>> changes=List.of(
                expected->expected.putArray("derive-sound-1"),
                expected->((ObjectNode)expected.path("derive-sound-1").get(1)).put("profileVersion","derive-rules-1"),
                expected->((ArrayNode)expected.path("derive-sound-1")).add("not-a-stamp"),
                expected->{ var array=(ArrayNode)expected.path("derive-sound-1");var first=array.get(0);for(int i=0;i<7;i++)array.add(first.deepCopy()); },
                expected->((ObjectNode)expected.path("derive-sound-1").get(0)).remove("preprocessingVersion"),
                expected->{ var stamp=expected.path("embeddings-bge-m3-1").deepCopy();var array=expected.putArray("embeddings-bge-m3-1");array.add(stamp);array.add(((ObjectNode)stamp.deepCopy()).put("model","other/model")); });
        for(int i=0;i<changes.size();i++) {
            var attestation=attestation(true);changes.get(i).accept((ObjectNode)attestation.path("expectedMeta"));
            assertFalse(configuration(attestation).configured(),"change "+i);
        }
    }

    private JsonNode fixtureMeta(String name) throws Exception {
        try(var in=getClass().getResourceAsStream("/ai-contract/operations/"+name+".json")) { return mapper.readTree(in).path("response").path("meta").deepCopy(); }
    }
    private ObjectNode attestation(boolean deriveArray) {
        var attestation=mapper.createObjectNode();
        var release=attestation.putObject("releaseManifest").put("kind","ai").put("image","example.invalid/ai@sha256:"+"1".repeat(64)).put("sourceSha","404dabf6cbe12621f4c86b84dc4dbda5b9750d5c");
        release.putObject("idf").put("sha256","3".repeat(64));
        release.putObject("model").put("profile","embeddings-bge-m3-1").put("name","BAAI/bge-m3").put("revision","5617a9f61b028005a4858fdac845db406aefb181");
        release.set("profiles",mapper.valueToTree(SyntheticPipelineServer.ATTESTED_META.keySet()));
        var expected=attestation.putObject("expectedMeta");
        for(var entry:SyntheticPipelineServer.ATTESTED_META.entrySet()) {
            if(entry.getValue().size()==1 || !deriveArray) expected.set(entry.getKey(),mapper.valueToTree(entry.getValue().getFirst()));
            else expected.set(entry.getKey(),mapper.valueToTree(entry.getValue()));
        }
        return attestation;
    }
    private AiPipelineConfiguration configuration(ObjectNode attestation) throws Exception {
        Path policy=temporary.resolve("policy.json"),ca=temporary.resolve("ca.pem"),proof=temporary.resolve("attestation.json");
        Files.writeString(policy,SyntheticPipelineServer.POLICY);Files.writeString(ca,"synthetic-ca-fingerprint-input");
        ((ObjectNode)attestation.path("releaseManifest")).putObject("tls").put("caSha256",AiResponseValidator.hash(Files.readString(ca)));
        Files.writeString(proof,attestation.toString());
        var env=new MockEnvironment();env.setActiveProfiles("prod");
        return new AiPipelineConfiguration(new AiProperties(true),new AiPipelineProperties(policy.toString(),proof.toString(),AiResponseValidator.hash(Files.readString(proof)),false,Map.of()),
                new AiClientProperties("https://ai.internal","synthetic-service-token",ca.toString(),Duration.ofSeconds(3),Duration.ofSeconds(130),Duration.ofSeconds(40),3,2,Duration.ofSeconds(2)),
                mapper,schemas,env);
    }
}
