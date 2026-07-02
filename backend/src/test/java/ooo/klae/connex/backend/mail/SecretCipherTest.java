package ooo.klae.connex.backend.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/**
 * Verifies AES/GCM secret encryption: round-trips, non-deterministic ciphertext,
 * tamper detection, and hard failure when no key is configured.
 */
class SecretCipherTest {

    private static SecretCipher withKey() {
        MailProperties props = new MailProperties();
        props.setSecretKey(Base64.getEncoder().encodeToString(new byte[32]));
        return new SecretCipher(props);
    }

    private static SecretCipher withoutKey() {
        return new SecretCipher(new MailProperties());
    }

    @Test
    void encryptThenDecrypt_roundTrips() {
        SecretCipher cipher = withKey();
        String secret = "sup3r-secret-smtp-passw0rd";
        String decrypted = cipher.decrypt(cipher.encrypt(secret));
        assertEquals(secret, decrypted);
    }

    @Test
    void encrypt_neverReturnsPlaintext_andIsNonDeterministic() {
        SecretCipher cipher = withKey();
        String secret = "plaintext-value";
        String first = cipher.encrypt(secret);
        String second = cipher.encrypt(secret);
        assertFalse(first.contains(secret), "ciphertext must not embed the plaintext");
        assertNotEquals(first, second, "random IV should make each ciphertext unique");
        assertEquals(secret, cipher.decrypt(first));
        assertEquals(secret, cipher.decrypt(second));
    }

    @Test
    void decrypt_tamperedBlob_fails() {
        SecretCipher cipher = withKey();
        String encoded = cipher.encrypt("value");
        byte[] blob = Base64.getDecoder().decode(encoded);
        blob[blob.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(blob);
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
    }

    @Test
    void noKey_isUnavailable_andRefusesToEncrypt() {
        SecretCipher cipher = withoutKey();
        assertFalse(cipher.isAvailable());
        assertThrows(BadRequestException.class, () -> cipher.encrypt("value"));
    }

    @Test
    void withKey_isAvailable() {
        assertTrue(withKey().isAvailable());
    }
}
