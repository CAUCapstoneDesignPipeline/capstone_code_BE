package com.example.capstone.analysis.config;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.NoSuchFileException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 외부 호출과 저장에서 공통 사용하며 pipeline/service 의존 순환을 만들지 않는다. */
@Component
public class AiFeatureGate {
    private final AiProperties flags;
    private final AiRuntimeProperties runtime;
    private final boolean development;
    private final boolean syntheticForbidden;
    public AiFeatureGate(AiProperties flags,AiRuntimeProperties runtime,AiPipelineProperties pipeline,Environment environment) {
        this.flags=flags;this.runtime=runtime;development=environment.matchesProfiles("local","test") && !environment.matchesProfiles("prod");
        syntheticForbidden=pipeline.syntheticStub() && !development;
    }
    public boolean allowed() {
        if(!flags.enabled() || syntheticForbidden || (!development && !runtime.releaseAuthorized())) return false;
        if(runtime.suspensionFile()==null || runtime.suspensionFile().isBlank()) return true;
        try { Files.readAttributes(Path.of(runtime.suspensionFile()),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return false; }
        catch(NoSuchFileException absent) { return true; }
        catch(Exception unreadable) { return false; }
    }
}
