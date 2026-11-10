package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.dto.ExportPermissions;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.dto.MemberDto;
import ooo.klae.connex.backend.dto.OrganizationLayoutWorkspaceMemberDto;
import ooo.klae.connex.backend.dto.RevokedInvitationDto;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;

/**
 * Mapper for workspace and membership persistence.
 */
public interface WorkspaceMapper {
    /** Returns the immutable successful control cutover deadline as UTC, or null if unavailable. */
    String findPendingInvitationCutoverDeadline();

    /** A bounded primary-key window includes active rows without locking them. */
    record PendingDeadlineRow(int userId, long membershipId, boolean needsBackfill) { }

    /** Reads at most limit memberships within one workspace, regardless of status or freshness. */
    List<PendingDeadlineRow> findPendingDeadlineWindow(@Param("workspaceId") int workspaceId,
        @Param("afterUserId") int afterUserId, @Param("limit") int limit);

    /** Persists the immutable cutover deadline only for the exact still-pending generation. */
    int backfillPendingDeadline(@Param("workspaceId") int workspaceId, @Param("userId") int userId,
        @Param("membershipId") long membershipId);

    List<Workspace> getWorkspacesForUser(int userId);
    List<WorkspaceMembershipDto> getMembershipsForUser(int userId);
    /** Discovers every membership scope without status or lifecycle filtering for final erasure. */
    List<Integer> discoverAccountMembershipWorkspaceIds(@Param("userId") int userId);
    /** Retains an exact erasure membership through the user index before its clustered row. */
    WorkspaceMember lockAccountMembershipForUpdate(
        @Param("workspaceId") int workspaceId, @Param("userId") int userId);
    /** Preserves owner NOWAIT semantics with the same user-index-first erasure acquisition. */
    WorkspaceMember lockAccountMembershipWithoutWaiting(
        @Param("workspaceId") int workspaceId, @Param("userId") int userId);
    /** Returns the exact active membership through a current shared-locking read. */
    WorkspaceMembershipDto getMembershipForUserForShare(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);
    Workspace getActiveById(@Param("workspaceId") int workspaceId);
    Workspace lockActiveIdentity(@Param("workspaceId") int workspaceId);
    Workspace getDefaultWorkspace();
    boolean isMember(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    /**
     * Locks the caller's active membership row for the given user ({@code SELECT ... FOR UPDATE}) and
     * returns the user id, or {@code null} when there is no active membership. Must be called inside a
     * transaction so the row lock is held; it serializes member-backed assignments against the offboarding
     * membership lock ({@code NotificationMapper.lockRecipientMemberships}) so a departing member cannot
     * receive a member-backed assignment concurrently with their removal.
     */
    Integer lockActiveMembership(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    WorkspaceMember lockAuthorizationMembership(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);
    /** Exact membership lock that refuses contention while a caller retains the workspace root. */
    WorkspaceMember lockAuthorizationMembershipWithoutWaiting(
        @Param("workspaceId") int workspaceId, @Param("userId") int userId);
    /** Retains exact membership authority without excluding other authorized readers. */
    WorkspaceMember lockAuthorizationMembershipForShare(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);
    WorkspaceMember getAuthorizationMembership(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);
    Long getMembershipGenerationId(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);
    boolean isMemberIncludingPending(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    List<Integer> getActiveMemberIdsIn(
        @Param("workspaceId") int workspaceId,
        @Param("userIds") List<Integer> userIds
    );
    /** Internal authored-reference provenance lookup; never returns recipient profiles. */
    List<Integer> getMemberIdsIncludingPending(
        @Param("workspaceId") int workspaceId,
        @Param("userIds") List<Integer> userIds
    );
    int countActiveMembers(
        @Param("workspaceId") int workspaceId,
        @Param("memberIds") List<Integer> memberIds
    );
    String getRole(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    List<User> getMembers(int workspaceId);
    String findPendingInvitationReferenceByEmail(@Param("workspaceId") int workspaceId,
        @Param("email") String email);

    Integer findPendingInvitationUserId(@Param("workspaceId") int workspaceId,
        @Param("reference") String reference);

    List<MemberDto> getMembersWithRoles(int workspaceId);
    MemberDto getMember(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    Integer getMemberRoleId(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    /** Locks the workspace's active owner rows without waiting, before acquiring role roots. */
    List<WorkspaceMember> lockActiveOwnerMembers(@Param("workspaceId") int workspaceId);
    /** Locks every membership currently overlaid by the role without waiting. */
    List<WorkspaceMember> lockRoleAssignees(
        @Param("workspaceId") int workspaceId,
        @Param("roleId") int roleId);
    int setMemberCustomRole(@Param("workspaceId") int workspaceId, @Param("userId") int userId, @Param("roleId") int roleId);
    java.util.List<Integer> workspaceIdsOwnedBy(@Param("userId") int userId);
    Integer lockWorkspace(@Param("workspaceId") int workspaceId);
    Integer lockWorkspaceForShare(@Param("workspaceId") int workspaceId);
    Integer lockActiveWorkspaceForShare(@Param("workspaceId") int workspaceId);
    Integer lockWorkspaceOrgIdForShare(@Param("workspaceId") int workspaceId);
    int removeMember(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    Integer getLastActiveWorkspaceId(int userId);
    int setLastActiveWorkspaceId(@Param("userId") int userId, @Param("workspaceId") int workspaceId);
    int clearLastActiveWorkspaceId(int userId);
    int insert(Workspace workspace);
    int updateIdentity(
        @Param("workspaceId") int workspaceId,
        @Param("name") String name,
        @Param("timezone") String timezone);
    int updateDefaultCurrency(
        @Param("workspaceId") int workspaceId,
        @Param("defaultCurrency") String defaultCurrency);
    Integer getOrgId(int workspaceId);
    List<Integer> findWorkspaceIds();
    List<Integer> findWorkspaceIdsPage(
        @Param("afterId") int afterId, @Param("limit") int limit);
    Integer findLastWorkspaceId();
    List<Integer> findWorkspaceIdsThroughPage(
        @Param("afterId") int afterId, @Param("throughId") int throughId, @Param("limit") int limit);
    List<Integer> findWorkspaceIdsLifecyclePage(
        @Param("afterId") int afterId, @Param("limit") int limit);
    /**
     * Loads the id, organization and name of every workspace in the anchor workspace's
     * organization, ordered by name then id. This is the control-plane snapshot record sharing
     * uses as its same-organization ceiling and as its workspace-name hydration source (#811), so
     * it deliberately applies no lifecycle filter — the {@code ShareMapper} joins it replaces
     * applied none either. {@link #findByOrgId} answers the same question for the read-path
     * ceilings and does filter on lifecycle, so the two statements return different sets for a
     * winding-down organization and neither may be substituted for the other.
     *
     * @param workspaceId workspace anchoring the organization
     * @return id/org/name rows in name then id order, empty when the anchor does not exist
     */
    List<Workspace> findOrganizationWorkspacesForShare(@Param("workspaceId") int workspaceId);

    /**
     * Loads the same unfiltered organization set for destructive cache erasure, flushing local
     * MyBatis state so committed workspace creation cannot be hidden by an earlier sharing read.
     */
    List<Workspace> findOrganizationWorkspacesForCachePurge(@Param("workspaceId") int workspaceId);

    /**
     * Loads every active workspace of an active organization, which is the scope the read-path
     * organization ceilings bind. It diverges from
     * {@link #findOrganizationWorkspacesForShare} by filtering on lifecycle state.
     *
     * @param orgId organization to resolve
     * @return the organization's active workspaces in id order
     */
    List<Workspace> findByOrgId(@Param("orgId") int orgId);
    List<Workspace> findActiveByOrgIdPage(
        @Param("orgId") int orgId,
        @Param("afterWorkspaceId") int afterWorkspaceId,
        @Param("limit") int limit);
    List<OrganizationLayoutWorkspaceMemberDto> findLayoutMemberships(
        @Param("orgId") int orgId,
        @Param("actorId") int actorId,
        @Param("workspaceIds") List<Integer> workspaceIds,
        @Param("memberLimit") int memberLimit);
    int countEnforcingSsoMemberships(int userId);
    int addMember(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("role") String role
    );
    int addPendingMember(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("role") String role
    );
    int activateMember(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    List<WorkspaceMembershipDto> getPendingMemberships(int userId);
    /**
     * Finds all of a user's pending grants in ascending workspace id, including grants in inactive
     * workspaces and organizations, so revocation cannot leave one behind.
     */
    List<RevokedInvitationDto> findPendingGrants(int userId);
    int removePendingMember(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    int updateMemberRole(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("role") String role
    );
    /** Returns export access for built-in roles in this exact control-plane scope. */
    ExportPermissions getExportPermissions(@Param("workspaceId") int workspaceId);
    /** Reads current policy while retaining the workspace root through transaction completion. */
    ExportPermissions getExportPermissionsForShare(@Param("workspaceId") int workspaceId);
    /** Replaces only the named built-in role's export access. */
    int updateExportPermission(@Param("workspaceId") int workspaceId, @Param("role") String role,
        @Param("enabled") boolean enabled);
}
