package ooo.klae.connex.backend.storage;

import java.util.regex.Pattern;

/**
 * Fail-closed validation for adapter-facing object keys.
 */
final class ObjectStorageKey {
    private static final Pattern KEY = Pattern.compile("^[a-z0-9][a-z0-9/_.-]{0,511}$");

    private ObjectStorageKey() {}

    static String requireValid(String key) {
        if (key == null || !KEY.matcher(key).matches()
                || key.startsWith("/") || key.endsWith("/")
                || key.contains("//") || key.contains("/../")
                || key.contains("/./") || key.endsWith("/..") || key.endsWith("/.")) {
            throw new ObjectStorageException("Invalid managed object key");
        }
        return key;
    }
}
