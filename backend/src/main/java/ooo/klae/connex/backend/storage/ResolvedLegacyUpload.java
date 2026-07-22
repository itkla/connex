package ooo.klae.connex.backend.storage;

import java.util.Objects;

/**
 * Bounded immutable legacy file loaded from the operator-supplied local root.
 *
 * @param fileName source file name
 * @param content source bytes
 */
record ResolvedLegacyUpload(String fileName, byte[] content) {
    ResolvedLegacyUpload {
        Objects.requireNonNull(fileName, "fileName");
        content = Objects.requireNonNull(content, "content").clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    long size() {
        return content.length;
    }
}
