package ooo.klae.connex.backend.signature;

import java.util.Objects;

/** Authenticated immutable signed-document bytes returned by an external provider callback. */
public record ProviderSignedArtifact(
        String contentType,
        byte[] bytes) {

    /** Validates the supported artifact media and takes defensive ownership of its bytes. */
    public ProviderSignedArtifact {
        if (!("application/json".equals(contentType) || "application/pdf".equals(contentType))) {
            throw new IllegalArgumentException("Provider signed-artifact content type is unsupported");
        }
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0) {
            throw new IllegalArgumentException("Provider signed artifact is empty");
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
