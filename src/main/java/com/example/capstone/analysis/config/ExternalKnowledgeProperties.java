package com.example.capstone.analysis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 추천 카드의 외부 지식(AI POST /external-knowledge). 결론·근거 문장이 모델 제공자로 나가고 호출마다 비용이 들어 기본은 꺼져 있다.
 * maxAttempts는 AI가 바쁘거나 응답이 늦을 때 같은 카드를 다시 부르는 최대 횟수다.
 */
@ConfigurationProperties("capstone.ai.external-knowledge")
public record ExternalKnowledgeProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("external-knowledge-llm-1") String profile, @DefaultValue("3") int maxAttempts) {
    public int attempts() { return Math.max(1,Math.min(maxAttempts,5)); }
}
