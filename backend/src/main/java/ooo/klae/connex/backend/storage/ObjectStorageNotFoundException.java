package ooo.klae.connex.backend.storage;

/**
 * Signals that a managed object key has no stored binary.
 */
public class ObjectStorageNotFoundException extends ObjectStorageException {
    public ObjectStorageNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }

    public ObjectStorageNotFoundException(String message) {
        super(message);
    }
}
