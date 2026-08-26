package ooo.klae.connex.backend.dto;

import java.util.Map;

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
 * @param userDisplayName    current display name of the named approver, null when unavailable
 * @param delegatedByUserId  approver who handed their seat over, set only on delegations
 * @param delegatedByDisplayName current display name of the delegator, null when unavailable
 * @param createdByUserId    actor who appended the fact, null when the scheduled sweep escalated
 * @param createdByDisplayName current display name of the actor, null when unavailable
 * @param comment            operator note recorded with the change
 * @param createdAt          when the fact was appended
 */
public record ApprovalStepAssignmentDto(
    int id,
    String assignmentKind,
    int assignmentRound,
    String approverKind,
    Integer userId,
    String userDisplayName,
    Integer delegatedByUserId,
    String delegatedByDisplayName,
    Integer createdByUserId,
    String createdByDisplayName,
    String comment,
    String createdAt
) {
    public static ApprovalStepAssignmentDto from(DocumentApprovalStepAssignment assignment) {
        return from(assignment, Map.of());
    }

    public static ApprovalStepAssignmentDto from(DocumentApprovalStepAssignment assignment,
            Map<Integer, String> memberLabels) {
        if (assignment == null) return null;
        return new ApprovalStepAssignmentDto(assignment.getId(), assignment.getAssignmentKind(),
            assignment.getAssignmentRound(), assignment.getApproverKind(), assignment.getUserId(),
            labelOf(memberLabels, assignment.getUserId()), assignment.getDelegatedByUserId(),
            labelOf(memberLabels, assignment.getDelegatedByUserId()), assignment.getCreatedByUserId(),
            labelOf(memberLabels, assignment.getCreatedByUserId()),
            assignment.getComment(), assignment.getCreatedAt());
    }

    private static String labelOf(Map<Integer, String> memberLabels, Integer userId) {
        return userId == null ? null : memberLabels.get(userId);
    }
}
