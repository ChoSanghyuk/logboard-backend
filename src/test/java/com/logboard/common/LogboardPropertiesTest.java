package com.logboard.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/** The app refuses to start without a strong MCP token or a master key. */
class LogboardPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(LogboardProperties.class)
    static class Config {}

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class)
            .withPropertyValues("logboard.storage.root=./data", "logboard.crypto.master-key=k",
                    "logboard.crop.before=20", "logboard.crop.after=10", "logboard.crop.expand-step=50",
                    "logboard.crop.max-lines=2000");

    @Test
    void tokenOf32CharsStarts() {
        runner.withPropertyValues("logboard.mcp.token=" + "a".repeat(32))
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void shortTokenRefusesToStartWithAClearMessage() {
        runner.withPropertyValues("logboard.mcp.token=" + "a".repeat(31)).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessages(ctx.getStartupFailure()))
                    .contains("LOGBOARD_MCP_TOKEN must be at least 32 characters");
        });
    }

    @Test
    void blankTokenOrMasterKeyRefusesToStart() {
        runner.withPropertyValues("logboard.mcp.token=").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessages(ctx.getStartupFailure())).contains("LOGBOARD_MCP_TOKEN is required");
        });
        runner.withPropertyValues("logboard.mcp.token=" + "a".repeat(40), "logboard.crypto.master-key=")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    static String rootMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (; t != null; t = t.getCause()) {
            sb.append(t.getMessage()).append('\n');
        }
        return sb.toString();
    }
}
