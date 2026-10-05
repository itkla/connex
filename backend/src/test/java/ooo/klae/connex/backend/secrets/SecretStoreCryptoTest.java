package ooo.klae.connex.backend.secrets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.SecretUnavailableException;

class SecretStoreCryptoTest {
    @Test
    void missingMasterKeyRefusesEncryption() {
        SecretStoreProperties properties = new SecretStoreProperties();
        SecretStoreCrypto crypto = new SecretStoreCrypto(properties);

        assertFalse(crypto.isAvailable());
        assertThrows(SecretUnavailableException.class, () -> crypto.encrypt("value", "aad"));
    }
}
