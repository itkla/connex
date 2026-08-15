package ooo.klae.connex.backend.dto;

import ooo.klae.connex.backend.beans.DocumentApprovalStepAssignment;

/**
 * One appended approver fact on a frozen approval step. The client renders these alongside the
 * frozen approver snapshot so both identities of a delegated seat stay visible.
 *
 * @param id                 assignment id
 * @param assignmentKind     delegation | escalation | reassignment
 * @param assignmentRound    reassignment generation this row belongs to; delegations are always 0
 * @param approverKind       user | any_approver
 * @param userId             named approver, null for {@code any_approver}
 * @param delegatedByUserId  approver who handed their seat over, set only on delegations
 * @param createdByUserId    actor who appended the fact, null when the scheduled sweep escalated
 * @param comment            operator note recorded with the change
 * @param createdAt          when the fact was appended
 */
public record ApprovalStepAssignmentDto(
    int id,
    String assignmentKind,
    int assignmentRound,
    String approverKind,
    Integer userId,
    Integer delegatedByUserId,
    Integer createdByUserId,
    String comment,
    String createdAt
) {
    public static ApprovalStepAssignmentDto from(DocumentApprovalStepAssignment assignment) {
        if (assignment == null) return null;
        return new ApprovalStepAssignmentDto(assignment.getId(), assignment.getAssignmentKind(),
            assignment.getAssignmentRound(), assignment.getApproverKind(), assignment.getUserId(),
            assignment.getDelegatedByUserId(), assignment.getCreatedByUserId(),
            assignment.getComment(), assignment.getCreatedAt());
    }
}
