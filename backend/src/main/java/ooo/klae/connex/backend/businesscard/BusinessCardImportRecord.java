package ooo.klae.connex.backend.businesscard;

import java.time.LocalDateTime;

/**
 * Tenant-scoped idempotency state for one confirmed business-card import.
 *
 * @param requestFingerprint versioned digest of the validated image and reviewed request
 * @param personId created or reused contact identifier, once complete
 * @param attachmentId retained card attachment identifier, once complete
 * @param companyId linked company identifier, when applicable
 * @param expiresAt earliest time at which the idempotency state may be retired
 * @param createdByUserId principal that created this claim; null is treated as unowned legacy state
 * @param submissionExpiresAt deadline for starting an import from an unbound reservation
 * @param reservationSlot bounded outstanding-reservation slot, released when the import binds
 */
public record BusinessCardImportRecord(
        byte[] requestFingerprint,
        Integer personId,
        Integer attachmentId,
        Integer companyId,
        LocalDateTime expiresAt,
        Integer createdByUserId,
        LocalDateTime submissionExpiresAt,
        Integer reservationSlot) {

    public BusinessCardImportRecord {
        requestFingerprint = requestFingerprint == null ? null : requestFingerprint.clone();
    }

    public BusinessCardImportRecord(
            byte[] requestFingerprint,
            Integer personId,
            Integer attachmentId,
            Integer companyId,
            LocalDateTime expiresAt) {
        this(requestFingerprint, personId, attachmentId, companyId, expiresAt, null, null, null);
    }

    @Override
    public byte[] requestFingerprint() {
        return requestFingerprint == null ? null : requestFingerprint.clone();
    }
}
