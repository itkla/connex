package ooo.klae.connex.backend.storage;

/**
 * Locked aggregate usage for one workspace's tenant-owned private objects.
 *
 * @param workspaceId owning workspace
 * @param usedBytes reserved object bytes
 * @param objectCount reserved object count
 */
public record WorkspaceObjectStorageQuota(int workspaceId, long usedBytes, int objectCount) {}
