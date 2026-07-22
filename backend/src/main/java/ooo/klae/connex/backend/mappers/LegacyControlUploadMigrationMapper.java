package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.storage.LegacyUploadRecord;

/**
 * Control-plane discovery and user-image persistence for the legacy upload migration.
 */
public interface LegacyControlUploadMigrationMapper {
    List<Integer> findWorkspaceIds(
        @Param("afterId") int afterId,
        @Param("limit") int limit);

    List<LegacyUploadRecord> findUserImages(
        @Param("afterId") int afterId,
        @Param("limit") int limit);

    int updateUserImage(
        @Param("id") int id,
        @Param("currentUrl") String currentUrl,
        @Param("newUrl") String newUrl);

    int countUserReferences();
}
