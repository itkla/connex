package ooo.klae.connex.backend.businesscard;

/**
 * Durable private-binary seam used by confirmed business-card imports.
 */
public interface BusinessCardBinaryStore {
    /**
     * Returns whether this deployment can durably retain imported card images.
     *
     * @return {@code true} when writes are available
     */
    boolean isReady();

    /**
     * Stores a validated business-card image under an opaque, workspace-bound key.
     * Implementations must compensate the object write if the surrounding transaction rolls back.
     *
     * @param workspaceId resolved workspace identifier
     * @param fileName safe display filename
     * @param contentType verified raster media type
     * @param content validated image bytes
     * @return durable attachment metadata
     */
    StoredBusinessCard store(int workspaceId, String fileName, String contentType, byte[] content);

    /**
     * Deletes or schedules deletion of an object created by {@link #store}.
     *
     * @param workspaceId resolved workspace identifier
     * @param url stored object URL
     */
    void delete(int workspaceId, String url);

    /**
     * Durable metadata returned after an object write.
     *
     * @param url private backend-authorized object URL
     * @param size stored byte count
     */
    record StoredBusinessCard(String url, long size) {
    }
}
