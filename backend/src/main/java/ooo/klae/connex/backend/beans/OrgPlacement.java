package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * Per-organization deployment placement record (#313 Phase 3). Describes where an
 * organization's data lives and its storage-encryption/key-custody posture. An
 * organization with no persisted row resolves to a synthetic {@code shared}
 * placement via {@link #sharedDefault(int)}; this bean carries no routing itself.
 */
@Data
public class OrgPlacement {
    private int orgId;
    private String placementMode;
    private String databaseHandle;
    private String storageEncryptionMode;
    private String keyController;
    private String kmsProvider;
    private String kmsKeyRef;
    private String kmsKeyRegion;
    private boolean revocationSupported;
    private String revocationEffect;
    private String backupEncryptionMode;
    private String snapshotCopyPolicy;
    private String restoreValidationState;
    private LocalDateTime evidenceCheckedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * The default placement for an organization with no persisted row: pooled
     * {@code shared} storage under provider-managed keys, no customer revocation.
     *
     * @param orgId the organization the placement belongs to
     * @return a synthetic shared placement, never persisted
     */
    public static OrgPlacement sharedDefault(int orgId) {
        OrgPlacement placement = new OrgPlacement();
        placement.setOrgId(orgId);
        placement.setPlacementMode("shared");
        placement.setStorageEncryptionMode("provider_managed");
        placement.setKeyController("connex_cloud_provider");
        placement.setRevocationSupported(false);
        return placement;
    }
}
