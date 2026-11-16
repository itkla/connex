package ooo.klae.connex.backend.util;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Generates, normalizes and digests privileged MFA attestation codes (#1534). A code is 80 random
 * bits written as 16 Crockford base32 characters and shown in four hyphenated groups. Input is
 * read case-insensitively without spaces or hyphens, and with Crockford's aliases ({@code O} as
 * {@code 0}, {@code I} and {@code L} as {@code 1}). Only a digest bound to the grantee is stored.
 */
public final class MfaAttestationCode {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int CODE_BYTES = 10;
    private static final int CODE_LENGTH = 16;
    private static final int GROUP_LENGTH = 4;
    private static final String DIGEST_DOMAIN = "connex-mfa-attestation:v1:";

    private MfaAttestationCode() {
    }

    /**
     * Generates a new code for display.
     *
     * @return 16 Crockford base32 characters in four hyphenated groups
     */
    public static String generate() {
        byte[] bytes = new byte[CODE_BYTES];
        RANDOM.nextBytes(bytes);
        StringBuilder code = new StringBuilder(CODE_LENGTH + CODE_LENGTH / GROUP_LENGTH - 1);
        int buffer = 0;
        int bufferedBits = 0;
        int written = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bufferedBits += 8;
            while (bufferedBits >= 5) {
                bufferedBits -= 5;
                if (written > 0 && written % GROUP_LENGTH == 0) {
                    code.append('-');
                }
                code.append(ALPHABET.charAt((buffer >> bufferedBits) & 0x1f));
                written++;
            }
        }
        return code.toString();
    }

    /**
     * Reads a code as typed into its canonical form.
     *
     * @param raw the code as entered, or null
     * @return the 16 canonical characters, or null when the input is not a well-formed code
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder canonical = new StringBuilder(CODE_LENGTH);
        for (char character : raw.toUpperCase(Locale.ROOT).toCharArray()) {
            if (character == ' ' || character == '-') {
                continue;
            }
            char mapped = switch (character) {
                case 'O' -> '0';
                case 'I', 'L' -> '1';
                default -> character;
            };
            if (ALPHABET.indexOf(mapped) < 0 || canonical.length() == CODE_LENGTH) {
                return null;
            }
            canonical.append(mapped);
        }
        return canonical.length() == CODE_LENGTH ? canonical.toString() : null;
    }

    /**
     * Digests a canonical code for one grantee, so a code can only ever match its own grantee.
     *
     * @param granteeUserId the account the code was issued to
     * @param canonicalCode a code returned by {@link #normalize(String)}
     * @return the lowercase SHA-256 hex digest to store or look up
     */
    public static String digest(int granteeUserId, String canonicalCode) {
        return OneTimeTokenDigest.sha256(DIGEST_DOMAIN + granteeUserId + ":" + canonicalCode);
    }
}
