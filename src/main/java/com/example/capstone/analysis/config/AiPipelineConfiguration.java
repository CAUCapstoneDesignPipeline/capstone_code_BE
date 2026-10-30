package com.example.capstone.analysis.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.example.capstone.analysis.client.AiCallException;
import com.example.capstone.analysis.client.AiOperation;
import com.example.capstone.analysis.client.AiResponseValidator;
import com.example.capstone.analysis.client.AiSchemaValidator;

/** 운영자가 제공한 정책·릴리스 증명과 응답 모델을 대조한다. */
@Component
public class AiPipelineConfiguration {
    private final Map<AiOperation,String> profiles=new LinkedHashMap<>();
    private final boolean synthetic;
    private JsonNode policy;
    private JsonNode attestation;
    private String fingerprint;
    private volatile boolean rejected;
    public AiPipelineConfiguration(AiProperties flags,AiPipelineProperties config,AiClientProperties client,ObjectMapper mapper,AiSchemaValidator schemas,Environment environment) {
        profiles.put(AiOperation.EXTRACT,"extract-det-3");profiles.put(AiOperation.EMBEDDINGS,"embeddings-bge-m3-1");profiles.put(AiOperation.MERGE,"merge-det-1");profiles.put(AiOperation.BRIDGES,"bridges-det-1");
        profiles.put(AiOperation.EDGES,"edges-det-1");profiles.put(AiOperation.EXPLAIN,"explain-llm-8");profiles.put(AiOperation.VERIFY_EXPLANATION,"explanation-det-3");profiles.put(AiOperation.LINKS,"links-det-1");profiles.put(AiOperation.FACTS,"facts-llm-1");profiles.put(AiOperation.DERIVE,"derive-sound-1");
        boolean development=environment.matchesProfiles("test","local") && !environment.matchesProfiles("prod");
        synthetic=development && config.syntheticStub();
        if(!flags.enabled()) return;
        if(config.syntheticStub() && !development) { rejected=true;return; }
        try {
            if(config.profiles()!=null) for(var entry:config.profiles().entrySet()) profiles.put(AiOperation.valueOf(entry.getKey().replace('-','_').toUpperCase(java.util.Locale.ROOT)),entry.getValue());
            if(config.policyFile()==null || config.policyFile().isBlank()) return;
            policy=read(mapper,config.policyFile());schemas.validate("Policy",policy);
            var types=new java.util.HashSet<String>();for(var type:policy.path("relationTypes")) AiResponseValidator.require(types.add(type.path("code").asString()));
            AiResponseValidator.require(!types.isEmpty());var compositions=new java.util.HashSet<String>();
            for(var rule:policy.path("compositionRules")) {
                AiResponseValidator.require(types.contains(rule.path("firstType").asString()) && types.contains(rule.path("secondType").asString()) && types.contains(rule.path("resultType").asString()));
                AiResponseValidator.require(compositions.add(rule.path("firstType").asString()+":"+rule.path("secondType").asString()));
            }
            if(!synthetic) {
                if(config.attestationFile()==null || config.attestationSha256()==null) { rejected=true;return; }
                if(Files.size(Path.of(config.attestationFile()))>2*1024*1024) { rejected=true;return; }
                String raw=Files.readString(Path.of(config.attestationFile()));
                AiResponseValidator.require(AiResponseValidator.hash(raw).equals(config.attestationSha256()));attestation=read(mapper,config.attestationFile());
                JsonNode release=attestation.path("releaseManifest");
                AiResponseValidator.require(release.path("kind").asString().equals("ai") && release.path("image").asString().matches(".+@sha256:[0-9a-f]{64}")
                    && release.path("sourceSha").asString().matches("[0-9a-f]{40}") && release.path("idf").path("sha256").asString().matches("[0-9a-f]{64}"));
                AiResponseValidator.require(profile(AiOperation.EMBEDDINGS).equals("embeddings-bge-m3-1") && release.path("model").path("profile").asString().equals("embeddings-bge-m3-1")
                    && release.path("model").path("revision").asString().equals("5617a9f61b028005a4858fdac845db406aefb181"));
                var manifestProfiles=new java.util.HashSet<String>();release.path("profiles").forEach(p->manifestProfiles.add(p.asString()));AiResponseValidator.require(manifestProfiles.containsAll(profiles.values()));
                for(String profile:profiles.values()) { JsonNode meta=attestation.path("expectedMeta").path(profile);schemas.validate("ModelStamp",meta);AiResponseValidator.require(meta.path("profileVersion").asString().equals(profile)); }
                AiResponseValidator.require(AiResponseValidator.hash(Files.readString(Path.of(client.caCertificate()))).equals(release.path("tls").path("caSha256").asString()));
            }
            fingerprint=AiResponseValidator.hash("pipeline-adopted.1:"+policy+":"+profiles+":"+(attestation==null?"synthetic-only":attestation.toString()));
        } catch(Exception invalid) { rejected=true;policy=null; }
    }
    private JsonNode read(ObjectMapper mapper,String path) throws java.io.IOException {
        Path file=Path.of(path);if(Files.size(file)>2*1024*1024) throw new java.io.IOException("Configuration exceeds bound");return mapper.readTree(Files.readString(file));
    }
    public boolean configured() { return policy!=null && fingerprint!=null && !rejected; }
    public String fingerprint() { return fingerprint; }
    public String profile(AiOperation operation) { return profiles.get(operation); }
    public Set<String> requiredProfiles() { return Set.copyOf(profiles.values()); }
    public JsonNode policy() { if(!configured()) throw new AiCallException(AiCallException.Reason.CONFIGURATION);return policy.deepCopy(); }
    public void verify(AiOperation operation,JsonNode meta) {
        if(!configured() || !profile(operation).equals(meta.path("profileVersion").asString())) reject();
        if(synthetic) {
            if(!Set.of("synthetic","fixture","test").contains(meta.path("provider").asString())) reject();
            return;
        }
        JsonNode expected=attestation.path("expectedMeta").path(profile(operation));
        for(String field:new String[]{"profileVersion","provider","model","modelRevision","promptVersion","schemaVersion","preprocessingVersion"}) if(!expected.path(field).equals(meta.path(field))) reject();
    }
    private void reject() { rejected=true;throw new AiCallException(AiCallException.Reason.PROFILE); }
}
