package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One approver assignment on an approval step. {@code approverKind} is {@code user} for a named
 * workspace member or {@code any_approver} for any member holding {@code DOCUMENT_APPROVE}. The
 * same shape backs both the policy template ({@code approval_policy_step_approver}) and the frozen
 * chain snapshot ({@code document_approval_step_approver}).
 */
@Data
@NoArgsConstructor
public class ApprovalStepApprover {
    private int id;
    private int workspaceId;
    private int stepId;
    private String approverKind;
    private Integer userId;
}
