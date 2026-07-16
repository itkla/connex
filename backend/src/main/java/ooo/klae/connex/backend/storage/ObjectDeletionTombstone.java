package ooo.klae.connex.backend.storage;

/**
 * Stable identity of one durable object-deletion row prepared before provider I/O.
 *
 * @param id queue row identifier
 * @param objectKey private adapter key protected by the row
 */
public record ObjectDeletionTombstone(long id, String objectKey) {
    public ObjectDeletionTombstone {
        if (id <= 0) {
            throw new IllegalArgumentException("Object-deletion tombstone id must be positive");
        }
        objectKey = ObjectStorageKey.requireValid(objectKey);
    }
}
