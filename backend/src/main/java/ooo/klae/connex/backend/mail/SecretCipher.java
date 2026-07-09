package ooo.klae.connex.backend.mail;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.secrets.SecretPurpose;
import ooo.klae.connex.backend.secrets.SecretReference;
import ooo.klae.connex.backend.secrets.SecretStore;

/**
 * Compatibility facade for workspace SMTP secrets. New writes go to the central
 * envelope secret store; legacy AES-GCM blobs remain decryptable through
 * {@code connex.mail.secret-key} so existing deployments do not lose access.
 */
@Component
public class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;
    private final SecretStore secretStore;

    public SecretCipher(MailProperties properties, SecretStore secretStore) {
        this.key = buildKey(properties.getSecretKey());
        this.secretStore = secretStore;
    }

    /**
     * Whether a usable secret-store or legacy encryption key is configured.
     * @return true when a key is available
     */
    public boolean isAvailable() {
        return secretStore.isAvailable() || key != null;
    }

    public boolean hasLegacyKey() {
        return key != null;
    }

    /**
     * Stores a workspace SMTP password in the central secret store.
     * @param workspaceId the owning workspace
     * @param plaintext the value to protect
     * @return the central secret-store reference
     */
    public String encryptForWorkspace(int workspaceId, String plaintext) {
        return secretStore.put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, plaintext);
    }

    /**
     * Decrypts a secret-store reference or a legacy Base64 AES-GCM blob.
     * @param encoded the stored secret reference or legacy encrypted blob
     * @return the recovered UTF-8 plaintext
     */
    public String decryptForWorkspace(int workspaceId, String encoded) {
        if (SecretReference.isReference(encoded)) {
            return secretStore.get(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, encoded);
        }
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

    /**
     * Legacy AES-GCM encryptor used only by compatibility tests.
     * @param plaintext the value to protect
     * @return the legacy encrypted blob
     */
    public String encryptLegacy(String plaintext) {
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

    public String decrypt(String encoded) {
        return decryptForWorkspace(0, encoded);
    }

    public void deleteReferenceForWorkspace(int workspaceId, String encoded) {
        secretStore.delete(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, encoded);
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
