package ooo.klae.connex.backend.delivery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Derives a non-secret identity for the exact provider configuration selected for one attempt. */
public final class DeliveryTargetFingerprint {

    private DeliveryTargetFingerprint() {
    }

    /**
     * Binds the adapter, configuration generation, endpoint/account identity, and opaque credential
     * reference without hashing or retaining credential material.
     *
     * @param providerId installed adapter id
     * @param configurationVersion provider configuration id and generation/version
     * @param endpointIdentity endpoint and non-secret account identity
     * @param credentialReference opaque credential reference, never a credential value
     * @return lowercase SHA-256 fingerprint
     */
    public static String create(
            String providerId,
            String configurationVersion,
            String endpointIdentity,
            String credentialReference) {
        String endpointCredentialHash = hash(
                require(endpointIdentity, "endpointIdentity"),
                require(credentialReference, "credentialReference"));
        return hash(
                require(providerId, "providerId"),
                require(configurationVersion, "configurationVersion"),
                endpointCredentialHash);
    }

    private static String require(String value, String name) {
        String required = Objects.requireNonNull(value, name).trim();
        if (required.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return required;
    }

    private static String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
