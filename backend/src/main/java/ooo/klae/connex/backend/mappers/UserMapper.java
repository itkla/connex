package ooo.klae.connex.backend.mappers;

import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.PasskeyApprovalGap;
import ooo.klae.connex.backend.beans.PrivilegedMfaStandingRow;
import ooo.klae.connex.backend.beans.PrivilegedMembershipRow;
import ooo.klae.connex.backend.beans.UnenrolledPrivilegedAccount;
import ooo.klae.connex.backend.beans.UnenrolledPrivilegedAccountCounts;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiChatRealtimeRecipientDto;
import ooo.klae.connex.backend.dto.UserDisplayNameDto;
import ooo.klae.connex.backend.dto.UserProfileHydrationRow;
import ooo.klae.connex.backend.dto.UserReferenceDto;
import ooo.klae.connex.backend.session.SessionEpochRestampGrant;

/**
 * Mpper interface for {@code User} persistence.
 * SQL is defined in {@code resources/mappers/UserMapper.xml}.
 * Used by {@code UserService} and {@code AuthService}.
 */

public interface UserMapper {
    List<User> getAllUsers();
    /** Existing control-plane identities only; callers supply a bounded, nonempty set. */
    List<Integer> findExistingIds(@Param("ids") List<Integer> ids);
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
    /**
     * Display-safe profiles for requested active members of one workspace, each paired with the
     * collation key of its display name. Never selects credentials.
     *
     * @param workspaceId workspace whose active members may be returned
     * @param ids nonempty candidate user ids
     * @return matching profiles ordered by display name, then id
     */
    List<UserProfileHydrationRow> getActiveWorkspaceMemberProfilesByIds(
        @Param("workspaceId") int workspaceId,
        @Param("ids") List<Integer> ids);
    List<Integer> findMatchingWorkspaceMemberIds(
        @Param("workspaceId") int workspaceId,
        @Param("query") String query);
    List<Integer> findMatchingWorkspaceMemberIdsIn(
        @Param("workspaceId") int workspaceId,
        @Param("query") String query,
        @Param("ids") List<Integer> ids);
    User getUserById(int id);
    User getUserByIdForShare(int id);
    Integer lockById(int id);
    Integer lockByIdForShare(int id);
    /**
     * Locks the custom roles assigned to the account {@code FOR SHARE}, so a concurrent permission
     * edit on them waits for the caller's transaction. The statement also clears the transaction's
     * MyBatis session cache, so privilege and passkey reads made after it query committed state
     * instead of returning answers cached earlier in the same transaction.
     *
     * @param id the account whose assigned custom roles are locked
     * @return the locked role ids in ascending order
     */
    List<Integer> lockAssignedCustomRoleIds(int id);
    List<Integer> lockAssignedCustomRoleRowsForShare(int id);
    boolean isAccountDeletionReserved(int id);
    /** IDs with live deletion reservations; callers supply a nonempty candidate set. */
    List<Integer> findAccountDeletionReservedIds(@Param("ids") List<Integer> ids);
    /** Returns current deletion-reservation state while retaining a shared user-row lock, or null if absent. */
    Boolean isAccountDeletionReservedForShare(int id);
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
    /**
     * The facts privileged-MFA standing is derived from, in one statement (#1534): whether the
     * account is privileged, whether it holds any passkey, and whether one of its passkeys is
     * sufficient, meaning it carries break-glass assurance or is covered in every organization where
     * the account is privileged.
     *
     * @param id the account
     * @return the three facts; never null
     */
    PrivilegedMfaStandingRow privilegedMfaStanding(int id);
    /**
     * Whether the passkey belongs to the account and is sufficient for its privileged MFA (#1534).
     *
     * @param userId the account
     * @param credentialRowId the passkey's {@code webauthn_credential.id}
     * @return true only for the account's own sufficient passkey
     */
    boolean isSufficientPasskey(@Param("userId") int userId, @Param("credentialRowId") int credentialRowId);
    /**
     * For each of the account's passkeys without break-glass assurance, the organizations where the
     * account is privileged and that passkey still needs approval (#1534).
     *
     * @param userId the account
     * @return one row per passkey and missing organization, ordered by passkey then organization
     */
    List<PasskeyApprovalGap> passkeyApprovalGaps(@Param("userId") int userId);
    /** Reads the account's active privileged membership sources, deduplicated and ordered by scope. */
    List<PrivilegedMembershipRow> privilegedMemberships(@Param("userId") int userId);
    /**
     * Whether the account holds a sufficient passkey other than the given one (#1534).
     *
     * @param userId the account
     * @param credentialRowId the passkey to leave out, by {@code webauthn_credential.id}
     * @return true when another of the account's passkeys is sufficient
     */
    boolean hasOtherSufficientPasskey(
            @Param("userId") int userId, @Param("credentialRowId") int credentialRowId);
    /**
     * The credential ids of the account's sufficient passkeys (#1534), in row order.
     *
     * @param userId the account
     * @return the raw WebAuthn credential ids; empty when none is sufficient
     */
    List<byte[]> sufficientPasskeyCredentialIds(@Param("userId") int userId);
    /**
     * Whether the passkey belongs to the account and vouches in the organization (#1534): it
     * carries break-glass assurance or is covered there.
     *
     * @param userId the account that must own the passkey
     * @param credentialRowId the passkey's {@code webauthn_credential.id}
     * @param orgId the organization
     * @return true only for the account's own passkey covered in that organization
     */
    boolean isPasskeyCoveredInOrganization(
            @Param("userId") int userId,
            @Param("credentialRowId") int credentialRowId,
            @Param("orgId") int orgId);
    /**
     * Whether any of the account's passkeys vouches in the organization (#1534).
     *
     * @param userId the account
     * @param orgId the organization
     * @return true when one of its passkeys carries break-glass assurance or is covered there
     */
    boolean hasPasskeyCoveredInOrganization(@Param("userId") int userId, @Param("orgId") int orgId);
    /**
     * The accounts among {@code userIds} that are privileged in the organization (#1534), read with
     * one statement however many there are. The list must not be empty.
     *
     * @param orgId the organization
     * @param userIds the accounts to test
     * @return those privileged in the organization
     */
    List<Integer> privilegedInOrganizationAmong(
            @Param("orgId") int orgId, @Param("userIds") Collection<Integer> userIds);
    /**
     * The accounts among {@code userIds} holding a passkey that vouches in the organization: one
     * covered there or carrying break-glass assurance (#1534). The list must not be empty.
     *
     * @param orgId the organization
     * @param userIds the accounts to test
     * @return those holding such a passkey
     */
    List<Integer> coveredInOrganizationAmong(
            @Param("orgId") int orgId, @Param("userIds") Collection<Integer> userIds);
    /** Counts privileged accounts that hold passkeys but no sufficient one (#1534). */
    long countAttestationRequiredAccounts();
    /** Counts per-organization coverage rows trusted by the privileged-MFA cutover (#1534). */
    long countGrandfatheredCoverage();
    /**
     * Privileged accounts with no enrolled passkey, in ascending id, capped at {@code limit}. Uses
     * the same privilege predicate as {@link #isPrivilegedAccount(int)}.
     */
    List<UnenrolledPrivilegedAccount> listUnenrolledPrivilegedAccounts(@Param("limit") int limit);
    /** Uncapped counts over the population {@link #listUnenrolledPrivilegedAccounts(int)} samples. */
    UnenrolledPrivilegedAccountCounts countUnenrolledPrivilegedAccounts();
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
    /** The account's current session epoch; sessions stamped with an older value are refused. */
    Integer currentSessionEpoch(int id);
    /**
     * Advances the account's session epoch and clears any previous recovery restamp grant in the
     * same update, refusing every session stamped before the bump.
     */
    int bumpSessionEpoch(int id);
    int grantEpochRestamp(
        @Param("id") int id,
        @Param("sessionPrimaryId") String sessionPrimaryId,
        @Param("epoch") int epoch);
    SessionEpochRestampGrant epochRestampGrant(int id);
    int clearEpochRestampGrant(int id);
    int updatePasswordHash(@Param("id") int id, @Param("passwordHash") String passwordHash);
    int updateTimezone(@Param("id") int id, @Param("timezone") String timezone);
    int updateLocale(@Param("id") int id, @Param("locale") String locale);
    int updateCreatedAt(@Param("id") int id, @Param("createdAt") String createdAt);
    int updateUpdatedAt(@Param("id") int id, @Param("updatedAt") String updatedAt);
}
