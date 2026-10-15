package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.ApprovalStepApprover;
import ooo.klae.connex.backend.beans.DocumentApproval;
import ooo.klae.connex.backend.beans.DocumentApprovalDecision;
import ooo.klae.connex.backend.beans.DocumentApprovalStep;
import ooo.klae.connex.backend.beans.DocumentApprovalStepAssignment;
import ooo.klae.connex.backend.dto.ApprovalImpactSummaryRow;
import ooo.klae.connex.backend.dto.ApprovalInboxRow;
import ooo.klae.connex.backend.dto.ApprovalInboxCursor;
import ooo.klae.connex.backend.dto.ApprovalReminderRow;

/**
 * Mapper for {@code document_approval} and its frozen chain;
 * workspace operations bind the workspace, while account-departure reads
 * and exact-key cleanup span the currently pinned tenant catalog.
 */
public interface DocumentApprovalMapper {
    List<DocumentApproval> getByDealId(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    List<DocumentApproval> getByDocumentId(@Param("workspaceId") int workspaceId, @Param("documentId") int documentId);
    List<DocumentApproval> getByIds(@Param("workspaceId") int workspaceId,
        @Param("ids") List<Integer> ids);
    DocumentApproval getById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    DocumentApproval getByIdForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id);
    DocumentApproval findPending(@Param("workspaceId") int workspaceId, @Param("documentId") int documentId);
    DocumentApproval findPendingForUpdate(@Param("workspaceId") int workspaceId,
        @Param("documentId") int documentId);
    List<DocumentApproval> findPendingByPolicyId(@Param("workspaceId") int workspaceId,
        @Param("policyId") int policyId);
    List<Integer> findPendingIdsByPolicyId(@Param("workspaceId") int workspaceId,
        @Param("policyId") int policyId);
    List<ApprovalImpactSummaryRow> findPendingImpactSummaries(
        @Param("workspaceId") int workspaceId,
        @Param("policyId") int policyId,
        @Param("limit") int limit);
    List<DocumentApproval> findPendingForWorkspace(@Param("workspaceId") int workspaceId,
        @Param("limit") int limit);
    List<DocumentApproval> findPendingForWorkspaceAfter(@Param("workspaceId") int workspaceId,
        @Param("afterId") int afterId, @Param("limit") int limit);
    int countPendingByPolicyId(@Param("workspaceId") int workspaceId, @Param("policyId") int policyId);
    int insert(DocumentApproval approval);
    int decide(@Param("workspaceId") int workspaceId, @Param("id") int id, @Param("status") String status,
        @Param("decidedBy") Integer decidedBy, @Param("decisionComment") String decisionComment,
        @Param("outcomeReason") String outcomeReason, @Param("outcomeDetail") String outcomeDetail);

    List<DocumentApprovalStep> getStepsByApprovalIds(@Param("workspaceId") int workspaceId,
        @Param("approvalIds") List<Integer> approvalIds);
    List<DocumentApprovalStep> getStepsByApprovalIdsForUpdate(@Param("workspaceId") int workspaceId,
        @Param("approvalIds") List<Integer> approvalIds);
    List<DocumentApprovalDecision> getDecisionsByApprovalIds(@Param("workspaceId") int workspaceId,
        @Param("approvalIds") List<Integer> approvalIds);
    List<DocumentApprovalDecision> getDecisionsByApprovalIdsForUpdate(
        @Param("workspaceId") int workspaceId, @Param("approvalIds") List<Integer> approvalIds);
    int insertStep(DocumentApprovalStep step);
    int insertStepApprover(ApprovalStepApprover approver);
    int insertDecision(DocumentApprovalDecision decision);
    int updateStepStatus(@Param("workspaceId") int workspaceId, @Param("id") int id,
        @Param("status") String status, @Param("expectedStatus") String expectedStatus);
    int cancelOpenSteps(@Param("workspaceId") int workspaceId, @Param("approvalId") int approvalId);

    List<DocumentApprovalStepAssignment> getAssignmentsByApprovalIds(
        @Param("workspaceId") int workspaceId, @Param("approvalIds") List<Integer> approvalIds);
    List<DocumentApprovalStepAssignment> getAssignmentsByApprovalIdsForUpdate(
        @Param("workspaceId") int workspaceId, @Param("approvalIds") List<Integer> approvalIds);
    int insertAssignment(DocumentApprovalStepAssignment assignment);
    int maxReassignmentRound(@Param("workspaceId") int workspaceId, @Param("stepId") int stepId);
    List<Integer> findExpiredActiveSteps(@Param("workspaceId") int workspaceId,
        @Param("approvalId") int approvalId);
    int escalateStep(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<ApprovalReminderRow> findReminderDueSteps(@Param("workspaceId") int workspaceId,
        @Param("approvalId") int approvalId);
    int advanceRemindedRound(@Param("workspaceId") int workspaceId, @Param("id") int id,
        @Param("round") int round, @Param("expectedRound") int expectedRound);
    List<ApprovalInboxRow> findActionableSteps(@Param("workspaceId") int workspaceId,
        @Param("userId") int userId, @Param("asOf") String asOf,
        @Param("after") ApprovalInboxCursor after, @Param("limit") int limit);

    /** Fresh, non-locking complete routing facts only for steps mentioning the departing user. */
    List<DocumentApprovalStep> findOpenStepsForDeparture(@Param("workspaceId") int workspaceId,
        @Param("userId") int userId);

    /** Fresh, non-locking complete routing facts for this user throughout the current tenant catalog. */
    List<DocumentApprovalStep> findOpenStepsForDepartureAnywhere(@Param("userId") int userId);

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
