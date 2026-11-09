package com.logboard.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;

/** MaskingEncoder around Boot's real encoders: plain pattern and structured ECS JSON. */
class MaskingEncoderTest {

    static String encode(String structuredFormat, String message, Throwable error) {
        LoggerContext ctx = new LoggerContext();
        ctx.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        ctx.putObject(Environment.class.getName(), new StandardEnvironment()); // as Spring Boot does
        MaskingEncoder encoder = new MaskingEncoder();
        encoder.setContext(ctx);
        encoder.setPattern("%level %msg%n%ex");
        encoder.setStructuredFormat(structuredFormat);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();
        Logger logger = ctx.getLogger("test");
        LoggingEvent event = new LoggingEvent(Logger.class.getName(), logger, Level.INFO, message, error, null);
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }

    @Test
    void plainPattern() {
        SecretMasker.register("plain-registered-secret");
        String out = encode("", "pw plain-registered-secret, Authorization: Bearer tok-123456",
                new IllegalStateException("password=leaked1"));
        assertThat(out).startsWith("INFO pw ****, Authorization: Bearer ****")
                .contains("java.lang.IllegalStateException: password=****")
                .doesNotContain("plain-registered-secret", "tok-123456", "leaked1");
    }

    @Test
    void structuredEcsJson() {
        SecretMasker.register("ecs-registered-secret");
        String pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA\n-----END RSA PRIVATE KEY-----";
        String out = encode("ecs", "upsert {\"apiKey\":\"sk-1 2\"} key " + pem + " pw ecs-registered-secret",
                new IllegalArgumentException("token=abc12345"));
        assertThat(out).startsWith("{\"@timestamp\"").contains("\"log\":{\"level\":\"INFO\"")
                .contains("\\\"apiKey\\\":\\\"****\\\"")
                .doesNotContain("sk-1 2", "MIIEowIBAAKCAQEA", "ecs-registered-secret", "abc12345");
    }
}
