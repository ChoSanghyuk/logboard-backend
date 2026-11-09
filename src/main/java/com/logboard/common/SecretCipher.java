package com.logboard.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import com.logboard.common.logging.SecretMasker;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for stored secrets (SSH password / private key, LLM apiKey).
 * Key = SHA-256 of {@code logboard.crypto.master-key}. Output = Base64(12-byte IV || ciphertext+tag).
 * Every plaintext seen here (and the master key) is registered with SecretMasker, so logs never show it.
 */
@Component
public class SecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(LogboardProperties props) {
        SecretMasker.register(props.crypto().masterKey());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(props.crypto().masterKey().getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String encrypt(String plain) {
        SecretMasker.register(plain);
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /** @throws IllegalStateException if the value was tampered with or encrypted with another master key */
    public String decrypt(String encoded) {
        byte[] all = Base64.getDecoder().decode(encoded);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            String plain = new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
            SecretMasker.register(plain);
            return plain;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed (wrong master key or corrupted value)", e);
        }
    }
}
