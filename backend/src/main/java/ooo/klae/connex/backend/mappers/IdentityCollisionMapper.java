package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.dto.IdentityCollisionGroupRow;
import ooo.klae.connex.backend.dto.IdentityCollisionMemberRow;

/**
 * Workspace-scoped persistence and live reads for the canonical identity collision report.
 */
public interface IdentityCollisionMapper {

    int deleteForWorkspace(@Param("workspaceId") int workspaceId);

    int insertPersonCollisionMembers(
        @Param("workspaceId") int workspaceId,
        @Param("rebuiltAt") LocalDateTime rebuiltAt);

    int insertCompanyCollisionMembers(
        @Param("workspaceId") int workspaceId,
        @Param("rebuiltAt") LocalDateTime rebuiltAt);

    List<IdentityCollisionGroupRow> findVisibleGroups(
        @Param("workspaceId") int workspaceId,
        @Param("recordType") String recordType,
        @Param("kind") String kind,
        @Param("limit") int limit,
        @Param("offset") long offset);

    long countVisibleGroups(
        @Param("workspaceId") int workspaceId,
        @Param("recordType") String recordType,
        @Param("kind") String kind);

    List<IdentityCollisionMemberRow> findVisibleMembers(
        @Param("workspaceId") int workspaceId,
        @Param("groups") List<IdentityCollisionGroupRow> groups);
}
