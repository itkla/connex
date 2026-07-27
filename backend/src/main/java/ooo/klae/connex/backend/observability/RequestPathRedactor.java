package ooo.klae.connex.backend.observability;

import java.util.Set;

/**
 * Collapses credential-bearing segments of a reported request path.
 *
 * <p>Two independent rules apply. A segment that directly follows one of the enumerable
 * token-bearing route prefixes — the {@code /invite}, {@code /invite-link} and {@code /unsubscribe}
 * client routes, their {@code /api/invites}, {@code /api/invite-links} and
 * {@code /api/delivery/unsubscribe} server counterparts, and the opaque managed-object routes — is
 * always replaced. Any other segment shaped like a generated credential (base64url alphabet, at
 * least 22 characters, mixed case with a digit) is replaced as defence in depth for routes added
 * later. Numeric identifiers, UUIDs and lowercase slugs are deliberately preserved so unmapped-path
 * and page-level triage stays legible.
 */
public final class RequestPathRedactor {
    static final String REDACTED_SEGMENT = "{token}";

    private static final int MIN_CREDENTIAL_LENGTH = 22;
    private static final Set<String> TOKEN_BEARING_PARENTS = Set.of(
            "invite",
            "invite-link",
            "invites",
            "invite-links",
            "unsubscribe",
            "content",
            "logo",
            "profile-picture");

    private RequestPathRedactor() {
    }

    /**
     * Returns the path with credential-bearing segments replaced by a fixed placeholder.
     *
     * @param path the raw request path, or null
     * @return the redacted path, or null when the path was null
     */
    public static String redact(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        String[] segments = path.split("/", -1);
        StringBuilder redacted = new StringBuilder(path.length());
        String previous = "";
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (index > 0) {
                redacted.append('/');
            }
            if (!segment.isEmpty() && (TOKEN_BEARING_PARENTS.contains(previous) || credentialShaped(segment))) {
                redacted.append(REDACTED_SEGMENT);
            } else {
                redacted.append(segment);
            }
            previous = segment;
        }
        return redacted.toString();
    }

    private static boolean credentialShaped(String segment) {
        if (segment.length() < MIN_CREDENTIAL_LENGTH) {
            return false;
        }
        boolean digit = false;
        boolean upper = false;
        boolean lower = false;
        for (int index = 0; index < segment.length(); index++) {
            char character = segment.charAt(index);
            if (character >= '0' && character <= '9') {
                digit = true;
            } else if (character >= 'A' && character <= 'Z') {
                upper = true;
            } else if (character >= 'a' && character <= 'z') {
                lower = true;
            } else if (character != '-' && character != '_') {
                return false;
            }
        }
        return digit && upper && lower;
    }
}
