package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.tenant.ControlWorkspaceLifecycleRegistry.TableLifecycle;

/** Registry-validated count and bounded deletion for control-plane workspace data. */
public interface ControlWorkspaceLifecycleMapper {
    long countRows(
        @Param("workspaceId") int workspaceId,
        @Param("declaration") TableLifecycle declaration);

    int deleteBatch(
        @Param("workspaceId") int workspaceId,
        @Param("declaration") TableLifecycle declaration,
        @Param("limit") int limit);
}
