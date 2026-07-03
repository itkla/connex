package ooo.klae.connex.backend.sso;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/**
 * Symmetric authenticated encryption for per-organization SSO secrets stored at
 * rest — currently the OIDC client secret. Delegates the AES/GCM primitive to
 * {@link AesGcm}; the key is the Base64-decoded {@code connex.sso.secret-key}.
 * When no key is configured, encryption is unavailable and callers that need it
 * fail loudly rather than persisting a recoverable secret in plaintext.
 */
@Component
public class SsoSecretCipher {

    private final SecretKeySpec key;

    public SsoSecretCipher(SsoProperties properties) {
        this.key = AesGcm.buildKey(properties.getSecretKey());
    }

    /**
     * Whether a usable encryption key is configured. When false, OIDC client
     * secrets cannot be stored.
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
        return AesGcm.encrypt(key, plaintext);
    }

    /**
     * Decrypts a Base64 {@code iv:ciphertext} blob produced by {@link #encrypt(String)}.
     * @param blob the Base64-encoded encrypted blob
     * @return the recovered UTF-8 plaintext
     */
    public String decrypt(String blob) {
        requireKey();
        return AesGcm.decrypt(key, blob);
    }

    private void requireKey() {
        if (key == null) {
            throw new BadRequestException(
                    "Cannot store an SSO client secret: no CONNEX_SSO_SECRET_KEY is configured on this instance");
        }
    }
}
