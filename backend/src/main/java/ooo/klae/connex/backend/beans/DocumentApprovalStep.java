package ooo.klae.connex.backend.beans;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One step of the chain frozen onto an approval request. The snapshot is taken when approval is
 * requested, so editing or deleting the policy afterwards cannot change an in-flight request.
 * Status is {@code pending} until the step opens, {@code active} while it collects decisions, then
 * {@code approved}, {@code rejected}, {@code cancelled}, {@code unsatisfiable}, or {@code expired}.
 *
 * <p>The deadline is frozen the same way: {@code dueIntervalHours} and {@code onExpiry} are copied
 * off the policy step, while {@code activatedAt} and {@code dueAt} are stamped when the step opens.
 * {@code assignments} are appended facts layered over {@code approvers}, which remains the immutable
 * frozen snapshot.
 */
@Data
@NoArgsConstructor
public class DocumentApprovalStep {
    private int id;
    private int workspaceId;
    private int approvalId;
    private int stepOrder;
    private String name;
    private int requiredCount;
    private String status;
    private String decidedAt;
    private Integer dueIntervalHours;
    private String onExpiry;
    private String activatedAt;
    private String dueAt;
    private String escalatedAt;
    private int remindedRound;
    private String createdAt;
    private String updatedAt;
    private List<ApprovalStepApprover> approvers = new ArrayList<>();
    private List<DocumentApprovalStepAssignment> assignments = new ArrayList<>();
    private List<DocumentApprovalDecision> decisions = new ArrayList<>();
}
