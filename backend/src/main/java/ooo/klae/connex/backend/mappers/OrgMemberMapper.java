package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.dto.OrgMemberDto;
import ooo.klae.connex.backend.dto.OrgMembershipDto;
import ooo.klae.connex.backend.dto.OrganizationLayoutAuthorityMemberDto;

/**
 * Persistence for organization memberships (the org control plane). Org-scoped
 * identity/authorization, not workspace-scoped — intentionally classified as
 * control-plane in {@code TenantScopeInterceptor}.
 */
public interface OrgMemberMapper {
    String getRole(@Param("orgId") int orgId, @Param("userId") int userId);
    String getRoleForUpdate(@Param("orgId") int orgId, @Param("userId") int userId);

    /**
     * Whether the account is an active member of an active workspace in the organization, which
     * is what an account must be before it can be given an organization membership (#1534).
     *
     * @param orgId the organization
     * @param userId the account
     * @return true for an existing tenant of the organization
     */
    boolean isActiveWorkspaceTenant(@Param("orgId") int orgId, @Param("userId") int userId);

    /**
     * Whether an add may address the account at all: it is an existing member of the active
     * organization, or an existing tenant of it (#1534). Unknown accounts answer exactly like
     * outsiders. Non-locking; the add revalidates under its locks.
     *
     * @param orgId the organization
     * @param userId the account
     * @return true for an org member or an active tenant
     */
    boolean isEligibleForMembership(@Param("orgId") int orgId, @Param("userId") int userId);

    /**
     * The account an add by email may address: one holding the address that is an existing member
     * of the active organization, or an existing tenant of it (#1534). Non-locking; the add
     * revalidates under its locks.
     *
     * @param orgId the organization
     * @param email the normalized address
     * @return the account id, or null for an unknown address and an outsider alike
     */
    Integer findEligibleUserIdByEmail(@Param("orgId") int orgId, @Param("email") String email);
    boolean isMember(@Param("orgId") int orgId, @Param("userId") int userId);
    int addMember(@Param("orgId") int orgId, @Param("userId") int userId, @Param("orgRole") String orgRole);

    /**
     * Records the founding owner of a newly created organization, flagged as its founder (#1534).
     *
     * @param orgId the organization just created
     * @param userId the founding owner
     * @return the number of rows inserted
     */
    int addFoundingMember(@Param("orgId") int orgId, @Param("userId") int userId);

    /**
     * Locks exactly one organization membership row, without touching the organization, which
     * the caller must already have validated and locked (#1534).
     *
     * @param orgId the organization
     * @param userId the member
     * @return the member's org role, or null when the account holds no row
     */
    String lockExactRole(@Param("orgId") int orgId, @Param("userId") int userId);

    /**
     * Adds a member or changes their role on behalf of another account, which ends any founder
     * attribution the member held (#1534).
     *
     * @param orgId the organization
     * @param userId the member whose role another account sets
     * @param orgRole the role to hold
     * @return the MySQL affected-row count
     */
    int addMemberClearingFounder(
            @Param("orgId") int orgId, @Param("userId") int userId, @Param("orgRole") String orgRole);
    int updateRole(@Param("orgId") int orgId, @Param("userId") int userId, @Param("orgRole") String orgRole);
    int removeMember(@Param("orgId") int orgId, @Param("userId") int userId);
    int countOwners(@Param("orgId") int orgId);
    List<Integer> lockOwnerIds(@Param("orgId") int orgId);
    List<Integer> orgIdsOwnedBy(@Param("userId") int userId);
    List<OrgMemberDto> getMembers(@Param("orgId") int orgId);
    List<OrganizationLayoutAuthorityMemberDto> findLayoutAuthorityMemberships(
        @Param("orgId") int orgId,
        @Param("afterUserId") int afterUserId,
        @Param("limit") int limit);
    List<OrgMembershipDto> getMembershipsForUser(@Param("userId") int userId);
}
