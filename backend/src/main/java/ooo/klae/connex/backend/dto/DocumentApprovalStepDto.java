package ooo.klae.connex.backend.dto;

import java.util.List;

import ooo.klae.connex.backend.beans.DocumentApprovalStep;

/**
 * One frozen step of an approval chain, with the approvals collected so far.
 *
 * @param id            step id
 * @param stepOrder     position in the chain, ascending from 1
 * @param name          operator label for the step
 * @param requiredCount distinct approvals needed before the step passes
 * @param approvedCount approvals collected so far
 * @param status        pending | active | approved | rejected | cancelled | unsatisfiable
 * @param decidedAt     when the step passed or was rejected
 * @param satisfiable   whether the open step can still reach its quorum
 * @param unsatisfiableReason reason the open step cannot reach its quorum
 * @param approvers     who may decide this step
 * @param decisions     the decisions recorded on this step
 */
public record DocumentApprovalStepDto(
    int id,
    int stepOrder,
    String name,
    int requiredCount,
    int approvedCount,
    String status,
    String decidedAt,
    boolean satisfiable,
    String unsatisfiableReason,
    List<ApprovalStepApproverDto> approvers,
    List<DocumentApprovalDecisionDto> decisions
) {
    public static DocumentApprovalStepDto from(DocumentApprovalStep s) {
        if (s == null) return null;
        int approvedCount = (int) s.getDecisions().stream()
            .filter(decision -> "approved".equals(decision.getDecision())).count();
        return new DocumentApprovalStepDto(s.getId(), s.getStepOrder(), s.getName(),
            s.getRequiredCount(), approvedCount, s.getStatus(), s.getDecidedAt(),
            true, null,
            s.getApprovers().stream().map(ApprovalStepApproverDto::from).toList(),
            s.getDecisions().stream().map(DocumentApprovalDecisionDto::from).toList());
    }

    public static DocumentApprovalStepDto from(DocumentApprovalStep s, boolean satisfiable,
            String unsatisfiableReason) {
        if (s == null) return null;
        int approvedCount = (int) s.getDecisions().stream()
            .filter(decision -> "approved".equals(decision.getDecision())).count();
        return new DocumentApprovalStepDto(s.getId(), s.getStepOrder(), s.getName(),
            s.getRequiredCount(), approvedCount, s.getStatus(), s.getDecidedAt(),
            satisfiable, unsatisfiableReason,
            s.getApprovers().stream().map(ApprovalStepApproverDto::from).toList(),
            s.getDecisions().stream().map(DocumentApprovalDecisionDto::from).toList());
    }
}
