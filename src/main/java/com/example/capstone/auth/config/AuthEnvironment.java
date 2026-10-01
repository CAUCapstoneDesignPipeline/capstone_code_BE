package com.example.capstone.auth.config;

import java.util.Arrays;
import org.springframework.core.env.Environment;

public final class AuthEnvironment {
    private AuthEnvironment() {
    }

    public static boolean isDevelopment(Environment environment) {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length > 0 && Arrays.stream(profiles)
                .allMatch(profile -> profile.equals("local") || profile.equals("test"));
    }
}
