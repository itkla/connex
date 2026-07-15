package ooo.klae.connex.backend.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Open private object stream and its trusted stored length.
 *
 * @param inputStream object content
 * @param contentLength stored byte length
 */
public record StoredObject(InputStream inputStream, long contentLength) implements AutoCloseable {
    public StoredObject {
        Objects.requireNonNull(inputStream, "inputStream");
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength must not be negative");
        }
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
