package ooo.klae.connex.backend.beans;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One step of an approval policy chain: an ordered position, the number of distinct approvals that
 * must be collected before the step passes, who may give them, and how long they have.
 *
 * <p>{@code dueIntervalHours} is how many hours the step may stay open once it activates; a
 * {@code null} interval means the step never expires and is never reminded. {@code onExpiry} is
 * {@code expire} to terminate the request at the deadline, or {@code escalate} to widen the step to
 * every approver once.
 */
@Data
@NoArgsConstructor
public class ApprovalPolicyStep {
    private int id;
    private int workspaceId;
    private int policyId;
    private int stepOrder;
    private String name;
    private int requiredCount;
    private Integer dueIntervalHours;
    private String onExpiry;
    private String createdAt;
    private String updatedAt;
    private List<ApprovalStepApprover> approvers = new ArrayList<>();
}
