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
