package ooo.klae.connex.backend.storage;

/**
 * Signals that the configured private object store could not complete an operation.
 */
public class ObjectStorageException extends RuntimeException {
    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public ObjectStorageException(String message) {
        super(message);
    }
}
