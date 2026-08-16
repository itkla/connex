package ooo.klae.connex.backend.dto;

import java.util.List;
import java.util.Map;

import ooo.klae.connex.backend.beans.DocumentApprovalStep;

/**
 * One frozen step of an approval chain, with the approvals collected so far.
 *
 * <p>{@code approvers} is the immutable snapshot taken when the chain was frozen, while
 * {@code assignments} are the appended delegation, escalation, and reassignment facts layered over
 * it. {@code effectiveApproverIds} is the server's resolution of both, so no client reimplements
 * the algorithm and both identities of a delegated seat stay visible.
 *
 * @param id            step id
 * @param stepOrder     position in the chain, ascending from 1
 * @param name          operator label for the step
 * @param requiredCount distinct approvals needed before the step passes
 * @param approvedCount approvals collected so far
 * @param status        pending | active | approved | rejected | cancelled | unsatisfiable | expired
 * @param decidedAt     when the step passed or was rejected
 * @param dueIntervalHours hours the step may stay open once it activates, null when it never expires
 * @param onExpiry      expire | escalate, frozen from the policy step
 * @param activatedAt   when the step opened for decisions
 * @param dueAt         absolute deadline, cleared once the step escalates
 * @param escalatedAt   when the step widened to every approver after passing its deadline
 * @param satisfiable   whether the open step can still reach its quorum
 * @param unsatisfiableReason reason the open step cannot reach its quorum
 * @param effectiveAnyApprover whether the step currently resolves to the whole approver pool
 * @param effectiveApproverIds members who may still decide this step
 * @param approvers     the frozen snapshot of who was assigned this step
 * @param assignments   appended delegation, escalation, and reassignment facts
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
    Integer dueIntervalHours,
    String onExpiry,
    String activatedAt,
    String dueAt,
    String escalatedAt,
    boolean satisfiable,
    String unsatisfiableReason,
    boolean effectiveAnyApprover,
    List<Integer> effectiveApproverIds,
    List<ApprovalStepApproverDto> approvers,
    List<ApprovalStepAssignmentDto> assignments,
    List<DocumentApprovalDecisionDto> decisions
) {
    public static DocumentApprovalStepDto from(DocumentApprovalStep s) {
        return from(s, true, null, false, List.of());
    }

    public static DocumentApprovalStepDto from(DocumentApprovalStep s, boolean satisfiable,
            String unsatisfiableReason, boolean effectiveAnyApprover,
            List<Integer> effectiveApproverIds) {
        return from(s, satisfiable, unsatisfiableReason, effectiveAnyApprover,
            effectiveApproverIds, Map.of());
    }

    public static DocumentApprovalStepDto from(DocumentApprovalStep s, boolean satisfiable,
            String unsatisfiableReason, boolean effectiveAnyApprover,
            List<Integer> effectiveApproverIds, Map<Integer, String> memberLabels) {
        if (s == null) return null;
        int approvedCount = (int) s.getDecisions().stream()
            .filter(decision -> "approved".equals(decision.getDecision())).count();
        return new DocumentApprovalStepDto(s.getId(), s.getStepOrder(), s.getName(),
            s.getRequiredCount(), approvedCount, s.getStatus(), s.getDecidedAt(),
            s.getDueIntervalHours(), s.getOnExpiry(), s.getActivatedAt(), s.getDueAt(),
            s.getEscalatedAt(), satisfiable, unsatisfiableReason,
            effectiveAnyApprover, effectiveApproverIds,
            s.getApprovers().stream().map(ApprovalStepApproverDto::from).toList(),
            s.getAssignments().stream()
                .map(assignment -> ApprovalStepAssignmentDto.from(assignment, memberLabels))
                .toList(),
            s.getDecisions().stream().map(DocumentApprovalDecisionDto::from).toList());
    }
}
