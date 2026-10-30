package com.example.capstone.analysis.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Verifies reusable input data, not the behavior of a production AI response validator. */
class SyntheticInputFixturesTest {
    @Test
    void fixturesUseNfcUtf8HashesAndUtf16HalfOpenSpans() throws Exception {
        try (var input = getClass().getResourceAsStream("/ai-input-boundaries/synthetic-inputs.json")) {
            assertThat(input).isNotNull();
            var root = JsonMapper.builder().build().readTree(input);
            assertThat(root.path("status").asString()).isEqualTo("test-input-only-not-api-contract");
            assertThat(root.path("cases").size()).isEqualTo(4);
            for (var fixture : root.path("cases")) {
                String body = fixture.path("storedNfcBody").asString();
                String raw = fixture.path("rawInput").asString();
                assertThat(fixture.path("synthetic").asBoolean()).isTrue();
                assertThat(Normalizer.normalize(raw, Normalizer.Form.NFC)).isEqualTo(body);
                assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(body.getBytes(StandardCharsets.UTF_8))))
                        .isEqualTo(fixture.path("bodySha256").asString());
                int start = fixture.path("startUtf16").asInt();
                int end = fixture.path("endUtf16").asInt();
                assertThat(start).isBetween(0, body.length() - 1);
                assertThat(end).isBetween(start + 1, body.length());
                assertThat(body.substring(start, end)).isEqualTo(fixture.path("quote").asString());
                assertThat(Character.isLowSurrogate(body.charAt(start))).isFalse();
                assertThat(Character.isHighSurrogate(body.charAt(end - 1))).isFalse();
                assertThat(body.length()).isEqualTo(fixture.path("utf16Length").asInt());
                assertThat(body.codePointCount(0, body.length())).isEqualTo(fixture.path("codePointLength").asInt());
            }
        }
    }
}
