package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.storage.LegacyUploadRecord;

/**
 * Workspace-scoped persistence for the operator-invoked legacy upload migration.
 */
public interface LegacyTenantUploadMigrationMapper {
    List<LegacyUploadRecord> findAttachments(
        @Param("workspaceId") int workspaceId,
        @Param("afterId") int afterId,
        @Param("limit") int limit);

    List<LegacyUploadRecord> findPersonImages(
        @Param("workspaceId") int workspaceId,
        @Param("afterId") int afterId,
        @Param("limit") int limit);

    List<LegacyUploadRecord> findCompanyImages(
        @Param("workspaceId") int workspaceId,
        @Param("afterId") int afterId,
        @Param("limit") int limit);

    int updateAttachment(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("currentUrl") String currentUrl,
        @Param("newUrl") String newUrl,
        @Param("fileName") String fileName,
        @Param("contentType") String contentType,
        @Param("size") long size);

    int updatePersonImage(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("currentUrl") String currentUrl,
        @Param("newUrl") String newUrl);

    int updateCompanyImage(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("currentUrl") String currentUrl,
        @Param("newUrl") String newUrl);

    int countReferences(@Param("workspaceId") int workspaceId);
}
