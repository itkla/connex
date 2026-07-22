package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.SuppressionEntry;

/** Data access for workspace-owned suppression entries. */
public interface SuppressionMapper {
    List<SuppressionEntry> getAll(@Param("workspaceId") int workspaceId);

    SuppressionEntry getById(@Param("workspaceId") int workspaceId, @Param("id") int id);

    void insert(SuppressionEntry entry);

    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);

    void clearCreatorsAnywhere(@Param("userId") int userId);
}
