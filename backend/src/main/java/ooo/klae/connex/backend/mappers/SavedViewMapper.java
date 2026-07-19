package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.SavedView;

/** Workspace-scoped persistence for saved-view definitions and visibility-aware reads. */
public interface SavedViewMapper {
    List<SavedView> getAccessibleByRecordType(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("recordType") String recordType);

    SavedView getAccessibleById(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("id") int id);

    SavedView getAccessibleByIdForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("id") int id);

    SavedView getOwnedByIdForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("id") int id);

    SavedView getByName(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("recordType") String recordType,
        @Param("name") String name);

    int insert(SavedView view);

    int update(SavedView view);

    int delete(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("id") int id);

    void deleteForUser(@Param("workspaceId") int workspaceId, @Param("userId") int userId);

    /** Deletes every saved view owned by a user across all workspaces during account erasure. */
    void deleteForUserAnywhere(@Param("userId") int userId);
}
