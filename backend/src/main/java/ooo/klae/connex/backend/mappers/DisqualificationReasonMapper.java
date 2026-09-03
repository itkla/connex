package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.DisqualificationReason;

/** Workspace-scoped persistence for the disqualification vocabulary (#559). */
public interface DisqualificationReasonMapper {
    List<DisqualificationReason> getAll(@Param("workspaceId") int workspaceId);

    DisqualificationReason getById(
        @Param("workspaceId") int workspaceId, @Param("id") int id);

    DisqualificationReason getByIdForUpdate(
        @Param("workspaceId") int workspaceId, @Param("id") int id);

    DisqualificationReason getByCode(
        @Param("workspaceId") int workspaceId, @Param("code") String code);

    DisqualificationReason getByCodeForUpdate(
        @Param("workspaceId") int workspaceId, @Param("code") String code);

    int insertBuiltIn(
        @Param("workspaceId") int workspaceId,
        @Param("code") String code,
        @Param("requiresNote") boolean requiresNote,
        @Param("position") int position);

    int insert(DisqualificationReason reason);

    int update(DisqualificationReason reason);

    int archive(@Param("workspaceId") int workspaceId, @Param("id") int id);

    int restore(@Param("workspaceId") int workspaceId, @Param("id") int id);
}
