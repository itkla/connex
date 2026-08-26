package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One appended approver fact layered over a frozen approval step. These rows are never edits: the
 * frozen {@link ApprovalStepApprover} snapshot stays byte-identical for the life of the request, and
 * the effective approver set is derived by replaying these facts over it.
 *
 * <p>{@code assignmentKind} is {@code delegation} when one approver hands their seat to another,
 * {@code escalation} when the current set is widened, and {@code reassignment} when the set is
 * replaced wholesale. A reassignment opens a new {@code assignmentRound}; only the highest round
 * resolves, so an earlier round's rows become inert rather than being deleted.
 */
@Data
@NoArgsConstructor
public class DocumentApprovalStepAssignment {
    private int id;
    private int workspaceId;
    private int approvalId;
    private int stepId;
    private String assignmentKind;
    private int assignmentRound;
    private String approverKind;
    private Integer userId;
    private Integer delegatedByUserId;
    private Integer createdByUserId;
    private String comment;
    private String createdAt;
}
