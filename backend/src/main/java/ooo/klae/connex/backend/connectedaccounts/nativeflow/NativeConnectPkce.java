package ooo.klae.connex.backend.connectedaccounts.nativeflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Cryptographic generation and hashing primitives for the native authorization state machine. */
final class NativeConnectPkce {
    private static final SecureRandom RANDOM = new SecureRandom();

    private NativeConnectPkce() {
    }

    static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] hash(String value) {
        return digest().digest(value.getBytes(StandardCharsets.UTF_8));
    }

    static String challenge(String verifier) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(hash(verifier));
    }

    static boolean matches(byte[] expectedHash, String value) {
        return expectedHash != null
            && MessageDigest.isEqual(expectedHash, hash(value));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
