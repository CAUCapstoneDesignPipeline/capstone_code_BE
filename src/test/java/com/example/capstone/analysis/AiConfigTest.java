package com.example.capstone.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.example.capstone.analysis.config.AiConfig;
import com.example.capstone.analysis.config.AiProperties;
import static org.assertj.core.api.Assertions.assertThat;

class AiConfigTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(AiConfig.class);

    @Test
    void missingOrFalseSettingRequiresNoAiProviderConfiguration() {
        context.run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(AiProperties.class).enabled()).isFalse();
        });
        context.withPropertyValues("capstone.ai.enabled=false").run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(AiProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void activationAndMalformedSettingsCannotSilentlyEnableOrMisreportFeatures() {
        context.withPropertyValues("capstone.ai.enabled=true").run(result -> {
            assertThat(result).hasFailed();
            assertThat(result.getStartupFailure()).hasRootCauseMessage("AI activation is not supported by this AI-off build");
        });
        context.withPropertyValues("capstone.ai.enabled=invalid").run(result -> assertThat(result).hasFailed());
    }
}
