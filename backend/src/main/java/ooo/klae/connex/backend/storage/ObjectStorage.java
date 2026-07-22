package ooo.klae.connex.backend.storage;

/**
 * Private binary storage used by managed Connex uploads.
 */
public interface ObjectStorage {
    void put(String key, UploadSource source, String contentType, byte[] sha256);
    StoredObject get(String key);
    void delete(String key);
    boolean isReady();
}
