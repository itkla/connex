package ooo.klae.connex.backend.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Generates high-entropy URL-safe tokens and their one-way SHA-256 storage digests. */
public final class OneTimeTokenDigest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private OneTimeTokenDigest() {
    }

    /** @return a new 256-bit URL-safe bearer value */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * @param rawToken bearer value to digest
     * @return lowercase SHA-256 hex digest
     */
    public static String sha256(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /**
     * @param first first lowercase hex digest
     * @param second second lowercase hex digest
     * @return whether both digests are equal without content-dependent early exit
     */
    public static boolean constantTimeEquals(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        return MessageDigest.isEqual(
            first.getBytes(StandardCharsets.US_ASCII),
            second.getBytes(StandardCharsets.US_ASCII));
    }
}
