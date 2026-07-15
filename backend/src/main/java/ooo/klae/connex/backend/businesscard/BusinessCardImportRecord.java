package ooo.klae.connex.backend.businesscard;

/**
 * Tenant-scoped idempotency state for one confirmed business-card import.
 *
 * @param requestFingerprint deterministic SHA-256 of the validated image and reviewed request
 * @param personId created contact identifier, once complete
 * @param attachmentId retained card attachment identifier, once complete
 * @param companyId linked company identifier, when applicable
 */
public record BusinessCardImportRecord(
        byte[] requestFingerprint,
        Integer personId,
        Integer attachmentId,
        Integer companyId) {

    public BusinessCardImportRecord {
        requestFingerprint = requestFingerprint == null ? null : requestFingerprint.clone();
    }

    @Override
    public byte[] requestFingerprint() {
        return requestFingerprint == null ? null : requestFingerprint.clone();
    }
}
