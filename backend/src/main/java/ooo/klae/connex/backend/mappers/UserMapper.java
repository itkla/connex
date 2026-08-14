package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiChatRealtimeRecipientDto;
import ooo.klae.connex.backend.dto.UserDisplayNameDto;
import ooo.klae.connex.backend.dto.UserReferenceDto;

/**
 * Mpper interface for {@code User} persistence.
 * SQL is defined in {@code resources/mappers/UserMapper.xml}.
 * Used by {@code UserService} and {@code AuthService}.
 */

public interface UserMapper {
    List<User> getAllUsers();
    /** Current display labels for the requested control-plane user ids. */
    List<UserDisplayNameDto> getDisplayNamesByIds(@Param("ids") List<Integer> ids);
    /** Authenticated destination identities for a bounded assistant fanout recipient set. */
    List<AiChatRealtimeRecipientDto> getActiveAiChatRealtimeRecipientsByIds(
        @Param("workspaceId") int workspaceId,
        @Param("ids") List<Integer> ids);
    /** Current display labels for requested active members of one workspace. */
    List<UserDisplayNameDto> getActiveWorkspaceMemberDisplayNamesByIds(
        @Param("workspaceId") int workspaceId,
        @Param("ids") List<Integer> ids);
    /** Current display references for requested active members of one workspace. */
    List<UserReferenceDto> getActiveWorkspaceMemberReferencesByIds(
        @Param("workspaceId") int workspaceId,
        @Param("ids") List<Integer> ids);
    User getUserById(int id);
    User getUserByIdForShare(int id);
    Integer lockById(int id);
    Integer lockByIdForShare(int id);
    boolean isAccountDeletionReserved(int id);
    boolean isAccountDeletionReservationOwner(
        @Param("id") int id, @Param("owner") String owner);
    int reserveAccountDeletion(
        @Param("id") int id, @Param("owner") String owner);
    int renewAccountDeletionReservation(
        @Param("id") int id, @Param("owner") String owner);
    int clearAccountDeletionReservation(
        @Param("id") int id, @Param("owner") String owner);
    User getUserByUsername(String username);
    User getUserByEmail(String email);
    /** Current account-wide administrative privilege from active control-plane memberships. */
    boolean isPrivilegedAccount(int id);
    /** Count of real accounts, excluding the reserved {@code __connex_system__} actor; gates bootstrap provisioning. */
    int countUsers();
    List<User> search(@Param("workspaceId") int workspaceId, @Param("query") String query);
    int insert(User user);
    int update(User user);
    int delete(int id);
    int updateLastLoginAt(int id);
    int updateProfilePictureUrlIfCurrent(
        @Param("id") int id,
        @Param("currentProfilePictureUrl") String currentProfilePictureUrl,
        @Param("profilePictureUrl") String profilePictureUrl);
    int updateDisplayName(@Param("id") int id, @Param("displayName") String displayName);
    int updateEmail(@Param("id") int id, @Param("email") String email);
    int markEmailVerified(int id);
    int updateUsername(@Param("id") int id, @Param("username") String username);
    int updatePasswordHash(@Param("id") int id, @Param("passwordHash") String passwordHash);
    int updateTimezone(@Param("id") int id, @Param("timezone") String timezone);
    int updateLocale(@Param("id") int id, @Param("locale") String locale);
    int updateCreatedAt(@Param("id") int id, @Param("createdAt") String createdAt);
    int updateUpdatedAt(@Param("id") int id, @Param("updatedAt") String updatedAt);
}
