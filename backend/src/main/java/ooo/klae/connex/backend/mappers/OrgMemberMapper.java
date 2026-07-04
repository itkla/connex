package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.dto.OrgMemberDto;
import ooo.klae.connex.backend.dto.OrgMembershipDto;

/**
 * Persistence for organization memberships (the org control plane). Org-scoped
 * identity/authorization, not workspace-scoped — intentionally classified as
 * control-plane in {@code TenantScopeInterceptor}.
 */
public interface OrgMemberMapper {
    String getRole(@Param("orgId") int orgId, @Param("userId") int userId);
    boolean isMember(@Param("orgId") int orgId, @Param("userId") int userId);
    int addMember(@Param("orgId") int orgId, @Param("userId") int userId, @Param("orgRole") String orgRole);
    int updateRole(@Param("orgId") int orgId, @Param("userId") int userId, @Param("orgRole") String orgRole);
    int removeMember(@Param("orgId") int orgId, @Param("userId") int userId);
    int countOwners(@Param("orgId") int orgId);
    List<Integer> lockOwnerIds(@Param("orgId") int orgId);
    List<Integer> orgIdsOwnedBy(@Param("userId") int userId);
    List<OrgMemberDto> getMembers(@Param("orgId") int orgId);
    List<OrgMembershipDto> getMembershipsForUser(@Param("userId") int userId);
}
