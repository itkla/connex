package ooo.klae.connex.backend.beans;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One step of an approval policy chain: an ordered position, the number of distinct approvals that
 * must be collected before the step passes, and who may give them.
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
    private String createdAt;
    private String updatedAt;
    private List<ApprovalStepApprover> approvers = new ArrayList<>();
}
