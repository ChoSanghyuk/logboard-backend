package com.logboard.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    static SecretCipher cipher(String masterKey) {
        return new SecretCipher(new LogboardProperties(null, new LogboardProperties.Crypto(masterKey), null, null));
    }

    private final SecretCipher cipher = cipher("master-key-1");

    @Test
    void roundTrip() {
        String plain = "p@ss wörd\n-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n";
        String enc = cipher.encrypt(plain);
        assertThat(enc).doesNotContain("p@ss");
        assertThat(cipher.decrypt(enc)).isEqualTo(plain);
    }

    @Test
    void randomIvGivesDifferentCiphertexts() {
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void otherMasterKeyCannotDecrypt() {
        String enc = cipher.encrypt("secret");
        assertThatThrownBy(() -> cipher("master-key-2").decrypt(enc)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperedValueIsRejected() {
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt("secret"));
        raw[raw.length - 1] ^= 1;
        String tampered = Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }
}
