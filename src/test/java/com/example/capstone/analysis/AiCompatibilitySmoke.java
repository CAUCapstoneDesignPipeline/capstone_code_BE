package com.example.capstone.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;
import com.example.capstone.analysis.client.*;
import com.example.capstone.analysis.config.AiClientProperties;

/** Explicit opt-in JavaExec task; never starts providers or reads production credentials. */
public final class AiCompatibilitySmoke {
    public static void main(String[] args) throws Exception {
        Path path=Path.of(System.getenv("CAPSTONE_AI_SMOKE_CONFIG_FILE"));
        if(Files.getPosixFilePermissions(path).stream().anyMatch(p->p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"))) throw new IllegalStateException("Smoke configuration must be private");
        var mapper=JsonMapper.builder().build(); var config=mapper.readTree(Files.readString(path));
        var environment=new MockEnvironment();environment.setActiveProfiles("test");
        var schemas=new AiSchemaValidator(mapper);var validator=new AiResponseValidator(schemas);
        var client=new AiHttpClient(new AiClientProperties(config.path("baseUrl").asString(),config.path("serviceToken").asString(),null,
                Duration.ofSeconds(3),Duration.ofSeconds(130),Duration.ofSeconds(40),1,1,Duration.ZERO),mapper,schemas,validator,environment);
        var results=new ArrayList<Map<String,Object>>();
        for(var item:config.path("cases")) {
            var operation=AiOperation.valueOf(item.path("operation").asString());
            var request=mapper.readTree(Files.readString(Path.of(item.path("requestFile").asString())));
            var result=client.call(operation,request,Instant.now().plusSeconds(130));
            results.add(Map.of("operation",operation.name(),"validated",true,"attempts",result.attempts(),"durationMs",result.durationMs()));
        }
        Files.writeString(path.resolveSibling("be-client-result.json"),mapper.writeValueAsString(Map.of("cases",results,"providerCalls",0,"scope","loopback synthetic compatibility only")));
        System.out.println("Validated actual AI HTTP operations: "+results.size());
    }
}
