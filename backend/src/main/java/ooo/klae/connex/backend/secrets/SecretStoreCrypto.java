package ooo.klae.connex.backend.secrets;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.exceptions.SecretUnavailableException;

/**
 * AES-GCM envelope encryption primitive for the database-backed secret store.
 * Each secret receives a fresh data key; the configured master key encrypts that
 * data key and is identified by {@code keyId} in storage metadata.
 */
@Component
public class SecretStoreCrypto {
    static final String KEY_ALGORITHM = "AES-GCM";
    static final String DATA_ALGORITHM = "AES-256-GCM";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int DATA_KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String activeKeyId;
    private final SecretKeySpec activeKeyEncryptionKey;
    private final Map<String, SecretKeySpec> keyEncryptionKeys;
    private final Set<String> disabledKeyIds;

    public SecretStoreCrypto(SecretStoreProperties properties) {
        this.activeKeyId = normalizeKeyId(properties.getKeyId());
        this.disabledKeyIds = normalizeKeyIds(properties.getDisabledKeyIds());
        Map<String, SecretKeySpec> built = new LinkedHashMap<>();
        Map<String, String> configuredKeys = properties.getKeys() == null ? Map.of() : properties.getKeys();
        for (Map.Entry<String, String> entry : configuredKeys.entrySet()) {
            String keyId = normalizeKeyId(entry.getKey());
            if (keyId == null) {
                continue;
            }
            built.put(keyId, buildRequiredKey(entry.getValue(), "connex.secret-store.keys." + keyId));
        }
        SecretKeySpec configuredActiveKey = buildOptionalKey(properties.getMasterKey(),
                "connex.secret-store.master-key");
        if (configuredActiveKey != null && activeKeyId != null) {
            built.put(activeKeyId, configuredActiveKey);
        }
        this.activeKeyEncryptionKey = activeKeyId == null ? null : built.get(activeKeyId);
        this.keyEncryptionKeys = Map.copyOf(built);
    }

    public boolean isAvailable() {
        return activeKeyEncryptionKey != null && !isDisabled(activeKeyId);
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    public boolean hasKey(String keyId) {
        String normalized = normalizeKeyId(keyId);
        return normalized != null && keyEncryptionKeys.containsKey(normalized) && !disabledKeyIds.contains(normalized);
    }

    public boolean hasConfiguredKey(String keyId) {
        String normalized = normalizeKeyId(keyId);
        return normalized != null && keyEncryptionKeys.containsKey(normalized);
    }

    public boolean isDisabled(String keyId) {
        String normalized = normalizeKeyId(keyId);
        return normalized != null && disabledKeyIds.contains(normalized);
    }

    public boolean isActiveKey(String keyId) {
        String normalized = normalizeKeyId(keyId);
        return normalized != null && normalized.equals(activeKeyId);
    }

    public Set<String> configuredKeyIds() {
        return keyEncryptionKeys.keySet();
    }

    public Set<String> disabledKeyIds() {
        return disabledKeyIds;
    }

    public EncryptedSecret encrypt(String plaintext, String aad) {
        requireKey();
        byte[] dataKey = new byte[DATA_KEY_BYTES];
        RANDOM.nextBytes(dataKey);
        SecretKeySpec dataKeySpec = new SecretKeySpec(dataKey, "AES");
        String ciphertext = encryptWithKey(dataKeySpec, plaintext.getBytes(StandardCharsets.UTF_8), aad);
        String encryptedDataKey = encryptWithKey(activeKeyEncryptionKey, dataKey, aad);
        return new EncryptedSecret(encryptedDataKey, ciphertext);
    }

    public String decrypt(String keyId, String encryptedDataKey, String ciphertext, String aad) {
        SecretKeySpec key = keyFor(keyId);
        byte[] dataKey;
        try {
            dataKey = decryptWithKey(key, encryptedDataKey, aad);
        } catch (IllegalStateException e) {
            throw new SecretUnavailableException("Encrypted integration secret key does not match stored secret", e);
        }
        byte[] plaintext = decryptWithKey(new SecretKeySpec(dataKey, "AES"), ciphertext, aad);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private void requireKey() {
        if (activeKeyEncryptionKey == null) {
            throw new SecretUnavailableException(
                    "No active CONNEX_SECRET_STORE key is configured");
        }
        if (isDisabled(activeKeyId)) {
            throw new SecretUnavailableException("The active CONNEX_SECRET_STORE key is disabled");
        }
    }

    private SecretKeySpec keyFor(String keyId) {
        String normalized = normalizeKeyId(keyId);
        if (isDisabled(normalized)) {
            throw new SecretUnavailableException("Encrypted integration secret key is disabled");
        }
        SecretKeySpec key = normalized == null ? null : keyEncryptionKeys.get(normalized);
        if (key == null) {
            throw new SecretUnavailableException("Encrypted integration secret key is not configured");
        }
        return key;
    }

    public boolean canUnwrapDataKey(String keyId, String encryptedDataKey, String aad) {
        try {
            decryptWithKey(keyFor(keyId), encryptedDataKey, aad);
            return true;
        } catch (SecretUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static SecretKeySpec buildOptionalKey(String base64Key, String propertyName) {
        if (base64Key == null || base64Key.isBlank()) {
            return null;
        }
        return buildRequiredKey(base64Key, propertyName);
    }

    private static SecretKeySpec buildRequiredKey(String base64Key, String propertyName) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(propertyName + " must not be blank");
        }
        byte[] raw = Base64.getDecoder().decode(base64Key.trim());
        if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
            throw new IllegalStateException(
                    propertyName + " must decode to a 16, 24, or 32 byte AES key");
        }
        return new SecretKeySpec(raw, "AES");
    }

    private static String normalizeKeyId(String keyId) {
        if (keyId == null) {
            return null;
        }
        String trimmed = keyId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Set<String> normalizeKeyIds(Set<String> keyIds) {
        Set<String> normalized = new LinkedHashSet<>();
        if (keyIds == null) {
            return normalized;
        }
        for (String keyId : keyIds) {
            String value = normalizeKeyId(keyId);
            if (value != null) {
                normalized.add(value);
            }
        }
        return Set.copyOf(normalized);
    }

    private static String encryptWithKey(SecretKeySpec key, byte[] plaintext, String aad) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] blob = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(ciphertext, 0, blob, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(blob);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt secret", e);
        }
    }

    private static byte[] decryptWithKey(SecretKeySpec key, String encoded, String aad) {
        try {
            byte[] blob = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(blob, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt secret", e);
        }
    }

    public record EncryptedSecret(String encryptedDataKey, String ciphertext) {
    }
}
