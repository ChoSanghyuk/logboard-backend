package com.logboard.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptRendererTest {

    @Test
    void replacesAllPlaceholdersInOnePass() {
        String out = PromptRenderer.render(
                "Server {{serverName}} on {{host}} at {{errorTime}} threw {{exceptionClass}}:\n{{errorLog}}\n{{unknown}} {{serverName}}",
                Map.of("errorLog", "line with {{serverName}} and $1 \\ chars", "serverName", "order-api", "host", "10.0.1.12",
                        "errorTime", "2026-11-08T14:31:58+09:00", "exceptionClass", "java.lang.NPE"));
        assertThat(out).isEqualTo("Server order-api on 10.0.1.12 at 2026-11-08T14:31:58+09:00 threw java.lang.NPE:\n"
                + "line with {{serverName}} and $1 \\ chars\n{{unknown}} order-api");
    }
}
