package com.example.capstone.auth.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class DevTokenCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return AuthEnvironment.isDevelopment(context.getEnvironment())
                && context.getEnvironment().getProperty("capstone.auth.dev-token.enabled", Boolean.class, false);
    }
}
