package ooo.klae.connex.backend.dto;

/** Detailed object-storage metadata remaining for one workspace. */
public record TenantStorageResidual(
        long activeObjectCount,
        long usageCount,
        long usageBytes,
        long pendingDeletionCount,
        long quotaRows,
        long quotaObjectCount,
        long quotaBytes) {

    /** Whether no storage metadata remains for the workspace. */
    public boolean clean() {
        return activeObjectCount == 0
            && usageCount == 0
            && usageBytes == 0
            && pendingDeletionCount == 0
            && quotaRows == 0
            && quotaObjectCount == 0
            && quotaBytes == 0;
    }
}
