package ooo.klae.connex.backend.secrets;

/**
 * Compact reference stored in feature tables when the secret body lives in the
 * central secret store.
 */
public record SecretReference(long id) {
    private static final String PREFIX = "secret:v1:";

    public String value() {
        return PREFIX + id;
    }

    public static boolean isReference(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public static SecretReference parse(String value) {
        if (!isReference(value)) {
            throw new IllegalArgumentException("Not a secret-store reference");
        }
        return new SecretReference(Long.parseLong(value.substring(PREFIX.length())));
    }

    public static SecretReference parseOrNull(String value) {
        if (!isReference(value)) {
            return null;
        }
        try {
            return new SecretReference(Long.parseLong(value.substring(PREFIX.length())));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
