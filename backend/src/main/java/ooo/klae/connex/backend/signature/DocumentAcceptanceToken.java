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

    private DocumentAcceptanceToken() {
    }

    /** Returns whether the complete bearer token has the accepted public syntax. */
    public static boolean hasValidShape(String token) {
        return token != null && TOKEN.matcher(token).matches();
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
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
