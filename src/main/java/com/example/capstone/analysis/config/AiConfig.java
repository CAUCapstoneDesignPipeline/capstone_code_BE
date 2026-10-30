package com.example.capstone.analysis.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@org.springframework.scheduling.annotation.EnableScheduling
@EnableConfigurationProperties({AiProperties.class, AiClientProperties.class, AiExecutionProperties.class, AiPipelineProperties.class})
public class AiConfig {
}
