package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.SavedView;
import java.util.List;

/**
 * Mapper for {@code SavedView} — per-user saved record-list views. SQL lives in
 * {@code resources/mappers/SavedViewMapper.xml}. Every statement is scoped to both the
 * active workspace AND the owning user, so a member can only ever reach their own views.
 */
public interface SavedViewMapper {
    List<SavedView> getByUser(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    List<SavedView> getByRecordType(@Param("workspaceId") int workspaceId, @Param("userId") int userId,
        @Param("recordType") String recordType);
    SavedView getById(@Param("workspaceId") int workspaceId, @Param("userId") int userId, @Param("id") int id);
    SavedView getByName(@Param("workspaceId") int workspaceId, @Param("userId") int userId,
        @Param("recordType") String recordType, @Param("name") String name);
    int insert(SavedView view);
    int update(SavedView view);
    int delete(@Param("workspaceId") int workspaceId, @Param("userId") int userId, @Param("id") int id);

    /**
     * Deletes every saved view owned by a user across all workspaces.
     * Offboarding replacement for the {@code saved_view.user_id} ON DELETE
     * CASCADE (#440 increment 3); personal data erased on account deletion.
     */
    void deleteForUserAnywhere(@Param("userId") int userId);

}
