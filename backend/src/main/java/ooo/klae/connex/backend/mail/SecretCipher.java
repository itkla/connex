package ooo.klae.connex.backend.mail;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/**
 * Symmetric authenticated encryption for secrets stored at rest — currently the
 * per-workspace SMTP password. Uses AES/GCM with a random 96-bit IV prepended to
 * the ciphertext; the whole blob is Base64 for column storage. The key is the
 * Base64-decoded {@code connex.mail.secret-key}. When no key is configured,
 * encryption is unavailable and callers that need it fail loudly rather than
 * persisting a recoverable secret in plaintext.
 */
@Component
public class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public SecretCipher(MailProperties properties) {
        this.key = buildKey(properties.getSecretKey());
    }

    /**
     * Whether a usable encryption key is configured. When false, per-workspace SMTP
     * passwords cannot be stored.
     * @return true when a key is available
     */
    public boolean isAvailable() {
        return key != null;
    }

    /**
     * Encrypts UTF-8 plaintext to a Base64 {@code iv:ciphertext} blob.
     * @param plaintext the value to protect
     * @return the Base64-encoded encrypted blob
     */
    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] blob = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(ciphertext, 0, blob, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(blob);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt secret", e);
        }
    }

    /**
     * Decrypts a Base64 {@code iv:ciphertext} blob produced by {@link #encrypt(String)}.
     * @param encoded the Base64-encoded encrypted blob
     * @return the recovered UTF-8 plaintext
     */
    public String decrypt(String encoded) {
        requireKey();
        try {
            byte[] blob = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(blob, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt secret", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new BadRequestException(
                    "Cannot store an SMTP password: no CONNEX_MAIL_SECRET_KEY is configured on this instance");
        }
    }

    private static SecretKeySpec buildKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            return null;
        }
        byte[] raw = Base64.getDecoder().decode(base64Key.trim());
        if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
            throw new IllegalStateException(
                    "connex.mail.secret-key must decode to a 16, 24, or 32 byte AES key");
        }
        return new SecretKeySpec(raw, "AES");
    }
}
