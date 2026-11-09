package com.logboard.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.logboard.common.LogboardProperties;
import com.logboard.common.SecretCipher;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Captures the real console output (logback-spring.xml) and checks that no secret reaches it.
 * The structured (ECS) variant is covered by MaskingEncoderTest: Boot applies the structured format only once per JVM.
 */
@Testcontainers
class LogMaskingIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static final Logger log = LoggerFactory.getLogger(LogMaskingIntegrationTest.class);
    static final String SSH_PASSWORD = "ssh-Pa55word-in-logs";
    static final String API_KEY = "sk-live-abcdef1234567890";
    static final String PEM = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAABG5vbmU\n-----END OPENSSH PRIVATE KEY-----";

    /** Logs every kind of secret the way careless code (or a library) might. */
    static void logSecrets(SecretCipher cipher, LogboardProperties props) {
        String stored = cipher.encrypt(SSH_PASSWORD);
        cipher.encrypt(API_KEY);
        cipher.decrypt(stored);
        log.info("connecting as app with {}", SSH_PASSWORD);
        log.info("LLM request headers: Authorization: Bearer {}", API_KEY);
        log.info("MCP token is {} and master key is {}", props.mcp().token(), props.crypto().masterKey());
        log.info("server upsert {\"name\":\"a\",\"password\":\"two words\",\"privateKey\":\"{}\"}", PEM.replace("\n", "\\n"));
        log.warn("auth failed", new IllegalStateException("bad key " + PEM + " for password=" + SSH_PASSWORD));
    }

    static void assertNoSecrets(CapturedOutput output, LogboardProperties props) {
        String out = output.getAll();
        assertThat(out).contains("connecting as app with ****");
        assertThat(out).doesNotContain(SSH_PASSWORD, API_KEY, props.mcp().token(), props.crypto().masterKey(),
                "b3BlbnNzaC1rZXktdjEAAAAABG5vbmU", "two words");
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @ExtendWith(OutputCaptureExtension.class)
    class PlainConsole {

        @Autowired SecretCipher cipher;
        @Autowired LogboardProperties props;

        @Test
        void secretsAreMasked(CapturedOutput output) {
            logSecrets(cipher, props);
            assertNoSecrets(output, props);
            assertThat(output.getAll()).contains("Authorization: Bearer ****")
                    .contains("-----BEGIN OPENSSH PRIVATE KEY-----****-----END OPENSSH PRIVATE KEY-----")
                    .contains("java.lang.IllegalStateException"); // stack trace still logged
        }
    }
}
