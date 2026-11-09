package com.logboard.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SecretMaskerTest {

    @Test
    void registeredValuesAreMaskedAnywhere() {
        SecretMasker.register("Sup3r-S3cret-PW-unit");
        assertThat(SecretMasker.mask("login failed for u with Sup3r-S3cret-PW-unit (retrying)"))
                .isEqualTo("login failed for u with **** (retrying)");
        SecretMasker.register("short"); // too short for literal masking
        assertThat(SecretMasker.mask("a short message")).isEqualTo("a short message");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Authorization: Bearer abc.DEF-123_xyz                | Authorization: Bearer ****
            header authorization=bearer tok3n-value-here          | header authorization=bearer ****
            password=hunter22 user=u                             | password=**** user=u
            {"name":"vllm","apiKey":"sk-live-123 456","x":1}     | {"name":"vllm","apiKey":"****","x":1}
            {\\"password\\":\\"p w\\"}                           | {\\"password\\":\\"****\\"}
            mcpToken: abcdefgh, next                             | mcpToken: ****, next
            privateKey = AAAAB3NzaC1yc2E                         | privateKey = ****
            master-key=xyz123456 ok                              | master-key=**** ok
            """)
    void patternsMaskValues(String in, String expected) {
        assertThat(SecretMasker.mask(in)).isEqualTo(expected);
    }

    @Test
    void pemPrivateKeysAreMaskedAlsoWhenJsonEscapedOrCutOff() {
        String pem = "key: -----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----\nnext";
        assertThat(SecretMasker.mask(pem))
                .isEqualTo("key: -----BEGIN OPENSSH PRIVATE KEY-----****-----END OPENSSH PRIVATE KEY-----\nnext");
        String escaped = "\"message\":\"-----BEGIN RSA PRIVATE KEY-----\\nMIIEow\\n-----END RSA PRIVATE KEY-----\"";
        assertThat(SecretMasker.mask(escaped)).doesNotContain("MIIEow");
        assertThat(SecretMasker.mask("x -----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBg (truncated")).isEqualTo(
                "x -----BEGIN PRIVATE KEY-----****");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Started LogboardApplication in 3.2 seconds",
            "tokenizer=standard password policy updated",
            "Fetched 1200 lines from /var/log/app.log",
            "Error log 20261108-001 → TERMINATED"})
    void ordinaryTextIsUntouched(String text) {
        assertThat(SecretMasker.mask(text)).isEqualTo(text);
    }

    @Test
    void maskingIsIdempotent() {
        String once = SecretMasker.mask("password=abc Authorization: Bearer xyz12345");
        assertThat(SecretMasker.mask(once)).isEqualTo(once);
    }
}
