package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.UserDashboard;

/**
 * Mapper for {@code UserDashboard} — a user's per-workspace dashboard layout. SQL lives in
 * {@code resources/mappers/UserDashboardMapper.xml}. Every statement is scoped to both the active
 * workspace AND the owning user, so a member can only ever reach their own layout. There is at
 * most one row per {@code (workspace_id, user_id)}, so writes go through {@code upsert}.
 */
public interface UserDashboardMapper {
    UserDashboard getByWorkspaceAndUser(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    int upsert(UserDashboard dashboard);
    int deleteByWorkspaceAndUser(@Param("workspaceId") int workspaceId, @Param("userId") int userId);

    /**
     * Deletes every dashboard layout owned by a user across all workspaces.
     * Offboarding replacement for the {@code user_dashboard.user_id} ON DELETE
     * CASCADE (#440 increment 3); personal data erased on account deletion.
     */
    void deleteForUserAnywhere(@Param("userId") int userId);

}
