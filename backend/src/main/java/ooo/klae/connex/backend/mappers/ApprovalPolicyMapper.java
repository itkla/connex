package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.ApprovalPolicy;
import ooo.klae.connex.backend.beans.ApprovalPolicyStep;
import ooo.klae.connex.backend.beans.ApprovalStepApprover;

/** Mapper for {@code approval_policy} and its chain steps; every statement is workspace-scoped. */
public interface ApprovalPolicyMapper {
    List<ApprovalPolicy> getAll(@Param("workspaceId") int workspaceId);
    List<ApprovalPolicy> getActive(@Param("workspaceId") int workspaceId);
    ApprovalPolicy getById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    int insert(ApprovalPolicy policy);
    int update(ApprovalPolicy policy);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);

    List<ApprovalPolicyStep> getStepsByPolicyIds(@Param("workspaceId") int workspaceId,
        @Param("policyIds") List<Integer> policyIds);
    int insertStep(ApprovalPolicyStep step);
    int insertStepApprover(ApprovalStepApprover approver);
    int deleteStepsByPolicyId(@Param("workspaceId") int workspaceId, @Param("policyId") int policyId);
}
