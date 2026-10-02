package ooo.klae.connex.backend.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.secrets.SecretPurpose;
import ooo.klae.connex.backend.secrets.SecretStore;

/**
 * Verifies AES/GCM secret encryption: round-trips, non-deterministic ciphertext,
 * tamper detection, and hard failure when no key is configured.
 */
class SecretCipherTest {

    private static SecretCipher withKey() {
        MailProperties props = new MailProperties();
        props.setSecretKey(Base64.getEncoder().encodeToString(new byte[32]));
        SecretStore secretStore = mock(SecretStore.class);
        when(secretStore.isAvailable()).thenReturn(true);
        return new SecretCipher(props, secretStore);
    }

    private static SecretCipher withoutKey() {
        SecretStore secretStore = mock(SecretStore.class);
        when(secretStore.isAvailable()).thenReturn(false);
        return new SecretCipher(new MailProperties(), secretStore);
    }

    /**
     * Readiness asks whether a stored password could be resolved. For a secret-store reference that is
     * an existence check on this workspace's SMTP slot, never a decrypt, so it writes no secret-use audit.
     */
    @Test
    void canResolveForWorkspaceChecksAReferenceWithoutDecryptingIt() {
        SecretStore secretStore = mock(SecretStore.class);
        SecretCipher cipher = new SecretCipher(new MailProperties(), secretStore);
        when(secretStore.exists(SecretPurpose.WORKSPACE_SMTP_PASSWORD, 7, "secret:v1:5")).thenReturn(true);
        when(secretStore.exists(SecretPurpose.WORKSPACE_SMTP_PASSWORD, 7, "secret:v1:6")).thenReturn(false);

        assertTrue(cipher.canResolveForWorkspace(7, "secret:v1:5"));
        assertFalse(cipher.canResolveForWorkspace(7, "secret:v1:6"));
        verify(secretStore, never()).get(any(), anyInt(), any());
    }

    @Test
    void canResolveForWorkspaceNeedsTheLegacyKeyForALegacyBlob() {
        String legacyBlob = withKey().encryptLegacy("smtp-password");

        assertTrue(withKey().canResolveForWorkspace(7, legacyBlob));
        assertFalse(withoutKey().canResolveForWorkspace(7, legacyBlob));
    }

    @Test
    void encryptThenDecrypt_roundTrips() {
        SecretCipher cipher = withKey();
        String secret = "sup3r-secret-smtp-passw0rd";
        String decrypted = cipher.decrypt(cipher.encryptLegacy(secret));
        assertEquals(secret, decrypted);
    }

    @Test
    void encrypt_neverReturnsPlaintext_andIsNonDeterministic() {
        SecretCipher cipher = withKey();
        String secret = "plaintext-value";
        String first = cipher.encryptLegacy(secret);
        String second = cipher.encryptLegacy(secret);
        assertFalse(first.contains(secret), "ciphertext must not embed the plaintext");
        assertNotEquals(first, second, "random IV should make each ciphertext unique");
        assertEquals(secret, cipher.decrypt(first));
        assertEquals(secret, cipher.decrypt(second));
    }

    @Test
    void decrypt_tamperedBlob_fails() {
        SecretCipher cipher = withKey();
        String encoded = cipher.encryptLegacy("value");
        byte[] blob = Base64.getDecoder().decode(encoded);
        blob[blob.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(blob);
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
    }

    @Test
    void noKey_isUnavailable_andRefusesToEncrypt() {
        SecretCipher cipher = withoutKey();
        assertFalse(cipher.isAvailable());
        assertThrows(BadRequestException.class, () -> cipher.encryptLegacy("value"));
    }

    @Test
    void withKey_isAvailable() {
        assertTrue(withKey().isAvailable());
    }

    @Test
    void mailPropertiesToStringRedactsSecrets() {
        MailProperties properties = new MailProperties();
        properties.setPassword("instance-password");
        properties.setSecretKey("legacy-key");
        String rendered = properties.toString();

        assertFalse(rendered.contains("instance-password"));
        assertFalse(rendered.contains("legacy-key"));
    }
}
