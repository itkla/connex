package ooo.klae.connex.backend.businesscard;

/**
 * Private original-image storage seam used by the business-card scanner.
 */
public interface BusinessCardBinaryStore {
    boolean isReady();

    StoredBusinessCard store(
        int workspaceId,
        String fileName,
        String contentType,
        byte[] content);

    void delete(int workspaceId, String url);

    /**
     * Opaque persisted reference for one stored original card image.
     *
     * @param url authenticated app-relative content URL
     * @param size stored byte length
     */
    record StoredBusinessCard(String url, long size) {}
}
