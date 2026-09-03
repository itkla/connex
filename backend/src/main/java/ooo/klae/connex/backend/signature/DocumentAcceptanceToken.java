package ooo.klae.connex.backend.signature;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Canonical syntax, routing, and hashing boundary for public document-acceptance tokens. */
public final class DocumentAcceptanceToken {
    private static final Pattern TOKEN = Pattern.compile("w(\\d+)-[a-f0-9]{64}");
    private static final int IMPOSSIBLE_WORKSPACE_ID = -1;
    private static final String IMPOSSIBLE = "w-1-" + "0".repeat(64);

    private DocumentAcceptanceToken() {
    }

    /** Returns whether the complete bearer token has the accepted public syntax. */
    public static boolean hasValidShape(String token) {
        if (token == null) {
            return false;
        }
        Matcher matcher = TOKEN.matcher(token);
        if (!matcher.matches()) {
            return false;
        }
        try {
            Integer.parseInt(matcher.group(1));
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    /**
     * Returns the token itself when shape-valid, otherwise a fixed sentinel whose negative
     * workspace identifier is excluded by both the public grammar and the positive-ID contract.
     */
    public static String canonicalizeForAdmission(String token) {
        return hasValidShape(token) ? token : IMPOSSIBLE;
    }

    /** Returns a real routing hint or the reserved negative sentinel for malformed input. */
    public static int workspaceIdForAdmission(String token) {
        return hasValidShape(token) ? workspaceId(token) : IMPOSSIBLE_WORKSPACE_ID;
    }

    /** Returns one stable digest for malformed admission while preserving real-token digests. */
    public static String hashForAdmission(String token) {
        return digest(canonicalizeForAdmission(token));
    }

    /** Returns the workspace routing hint from a shape-valid token. */
    public static int workspaceId(String token) {
        Matcher matcher = token == null ? TOKEN.matcher("") : TOKEN.matcher(token);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid document-acceptance token");
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid document-acceptance token", exception);
        }
    }

    /** Returns the lowercase SHA-256 digest used for admission and persistence lookup. */
    public static String hash(String token) {
        if (!hasValidShape(token)) {
            throw new IllegalArgumentException("Invalid document-acceptance token");
        }
        return digest(token);
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
