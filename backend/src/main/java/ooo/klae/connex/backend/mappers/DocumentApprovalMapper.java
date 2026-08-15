package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.ApprovalStepApprover;
import ooo.klae.connex.backend.beans.DocumentApproval;
import ooo.klae.connex.backend.beans.DocumentApprovalDecision;
import ooo.klae.connex.backend.beans.DocumentApprovalStep;
import ooo.klae.connex.backend.dto.ApprovalImpactSummaryRow;

/** Mapper for {@code document_approval} and its frozen chain; every statement is workspace-scoped. */
public interface DocumentApprovalMapper {
    List<DocumentApproval> getByDealId(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    List<DocumentApproval> getByDocumentId(@Param("workspaceId") int workspaceId, @Param("documentId") int documentId);
    DocumentApproval getById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    DocumentApproval findPending(@Param("workspaceId") int workspaceId, @Param("documentId") int documentId);
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
    List<DocumentApprovalDecision> getDecisionsByApprovalIds(@Param("workspaceId") int workspaceId,
        @Param("approvalIds") List<Integer> approvalIds);
    int insertStep(DocumentApprovalStep step);
    int insertStepApprover(ApprovalStepApprover approver);
    int insertDecision(DocumentApprovalDecision decision);
    int updateStepStatus(@Param("workspaceId") int workspaceId, @Param("id") int id,
        @Param("status") String status, @Param("expectedStatus") String expectedStatus);
    int cancelOpenSteps(@Param("workspaceId") int workspaceId, @Param("approvalId") int approvalId);
    int countStepApprovals(@Param("workspaceId") int workspaceId, @Param("stepId") int stepId);
}
