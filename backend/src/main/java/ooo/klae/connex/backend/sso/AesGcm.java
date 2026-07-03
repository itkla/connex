package ooo.klae.connex.backend.sso;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stateless AES/GCM authenticated encryption helper for secrets stored at rest.
 * A random 96-bit IV is prepended to the ciphertext and the whole blob is Base64
 * for column storage; the key is a Base64-decoded 128/192/256-bit AES key. Callers
 * own key lifecycle and availability checks (see {@code SsoSecretCipher}).
 */
final class AesGcm {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcm() {
    }

    /**
     * Builds an AES key from a Base64-encoded 16/24/32-byte value.
     * @param base64Key the Base64-encoded key, or null/blank when none is configured
     * @return the key, or null when no key is configured
     */
    static SecretKeySpec buildKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            return null;
        }
        byte[] raw = Base64.getDecoder().decode(base64Key.trim());
        if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
            throw new IllegalStateException(
                    "connex.sso.secret-key must decode to a 16, 24, or 32 byte AES key");
        }
        return new SecretKeySpec(raw, "AES");
    }

    /**
     * Encrypts UTF-8 plaintext to a Base64 {@code iv:ciphertext} blob.
     * @param key the AES key
     * @param plaintext the value to protect
     * @return the Base64-encoded encrypted blob
     */
    static String encrypt(SecretKeySpec key, String plaintext) {
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
     * Decrypts a Base64 {@code iv:ciphertext} blob produced by {@link #encrypt(SecretKeySpec, String)}.
     * @param key the AES key
     * @param blob the Base64-encoded encrypted blob
     * @return the recovered UTF-8 plaintext
     */
    static String decrypt(SecretKeySpec key, String blob) {
        try {
            byte[] decoded = Base64.getDecoder().decode(blob);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(decoded, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(decoded, IV_BYTES, decoded.length - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt secret", e);
        }
    }
}
