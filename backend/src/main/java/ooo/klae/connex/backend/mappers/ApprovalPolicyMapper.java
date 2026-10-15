package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.ApprovalPolicy;
import ooo.klae.connex.backend.beans.ApprovalPolicyStep;
import ooo.klae.connex.backend.beans.ApprovalStepApprover;

/**
 * Mapper for {@code approval_policy} and its chain steps;
 * workspace operations bind the workspace, while account-departure reads
 * and exact-key cleanup span the currently pinned tenant catalog.
 */
public interface ApprovalPolicyMapper {
    List<ApprovalPolicy> getAll(@Param("workspaceId") int workspaceId);
    List<ApprovalPolicy> getAllForUpdate(@Param("workspaceId") int workspaceId);
    List<ApprovalPolicy> getActive(@Param("workspaceId") int workspaceId);
    ApprovalPolicy getById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    ApprovalPolicy getByIdForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id);
    int insert(ApprovalPolicy policy);
    int update(ApprovalPolicy policy);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);

    ApprovalPolicy getWithStepsById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<ApprovalPolicyStep> getStepsByPolicyIds(@Param("workspaceId") int workspaceId,
        @Param("policyIds") List<Integer> policyIds);
    List<ApprovalPolicyStep> getStepsByPolicyIdsForUpdate(@Param("workspaceId") int workspaceId,
        @Param("policyIds") List<Integer> policyIds);
    int insertStep(ApprovalPolicyStep step);
    int insertStepApprover(ApprovalStepApprover approver);
    int deleteStepsByPolicyId(@Param("workspaceId") int workspaceId, @Param("policyId") int policyId);

    /** Fresh, non-locking complete routing facts only for steps mentioning the departing user. */
    List<ApprovalPolicyStep> findActiveStepsForDeparture(@Param("workspaceId") int workspaceId,
        @Param("userId") int userId);

    /** Fresh, non-locking complete routing facts for this user throughout the current tenant catalog. */
    List<ApprovalPolicyStep> findActiveStepsForDepartureAnywhere(@Param("userId") int userId);

    /** Discovers every frozen or template approver reference, including terminal and inactive ones. */
    List<ApprovalStepApprover> findDepartureReferences(@Param("workspaceId") int workspaceId,
        @Param("userId") int userId);

    /** Discovers every approver reference in this catalog. */
    List<ApprovalStepApprover> findDepartureReferencesAnywhere(@Param("userId") int userId);

    /** Deletes the exact discovered row only if it still references the departing member. */
    int deleteDepartingApprover(@Param("workspaceId") int workspaceId, @Param("id") int id,
        @Param("userId") int userId);

    /** Identity-scoped catalog-erasure twin; retains the exact workspace, row, and user predicate. */
    int deleteDepartingApproverAnywhere(@Param("workspaceId") int workspaceId, @Param("id") int id,
        @Param("userId") int userId);
}
