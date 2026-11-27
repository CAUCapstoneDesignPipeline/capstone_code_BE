package com.example.capstone.auth.config;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 개발자 전용 API를 쓸 수 있는 계정의 이메일 목록. 비어 있으면 아무도 쓸 수 없다. */
@ConfigurationProperties("capstone.developer")
public record DeveloperProperties(List<String> emails) {

    public Set<String> normalizedEmails() {
        if (emails == null) {
            return Set.of();
        }
        return emails.stream().map(DeveloperProperties::normalize).filter(email -> !email.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public static String normalize(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }
}
