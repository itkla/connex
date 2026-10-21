package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AiStandingApprovalPolicy;

/** Organization-scoped control configuration, always reached through the control route. */
public interface AiStandingApprovalPolicyMapper {
    AiStandingApprovalPolicy get(int orgId);
    AiStandingApprovalPolicy lockForUpdate(int orgId);
    AiStandingApprovalPolicy lockForShare(int orgId);
    int provision(int orgId);
    List<Integer> findMissing(@Param("afterOrgId") int afterOrgId, @Param("limit") int limit);
    List<Integer> administratorIds(int orgId);
    int replace(AiStandingApprovalPolicy policy);
}
