package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.storage.WorkspaceObjectStorageQuota;

/**
 * Atomic tenant-object quota ledger persistence.
 */
public interface ObjectStorageQuotaMapper {
    int ensureQuota(@Param("workspaceId") int workspaceId);

    WorkspaceObjectStorageQuota lockQuota(@Param("workspaceId") int workspaceId);

    WorkspaceObjectStorageQuota findQuota(@Param("workspaceId") int workspaceId);

    Long lockUsageSize(
        @Param("workspaceId") int workspaceId,
        @Param("objectKey") String objectKey);

    int insertUsage(
        @Param("workspaceId") int workspaceId,
        @Param("objectKey") String objectKey,
        @Param("sizeBytes") long sizeBytes);

    int addToQuota(
        @Param("workspaceId") int workspaceId,
        @Param("sizeBytes") long sizeBytes);

    int deleteUsage(
        @Param("workspaceId") int workspaceId,
        @Param("objectKey") String objectKey);

    int subtractFromQuota(
        @Param("workspaceId") int workspaceId,
        @Param("sizeBytes") long sizeBytes);
}
