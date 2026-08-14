package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One approver's immutable decision on one step of an approval chain. A step reaches its quorum
 * when it holds {@code requiredCount} distinct {@code approved} decisions; a single {@code rejected}
 * decision terminates the whole approval.
 */
@Data
@NoArgsConstructor
public class DocumentApprovalDecision {
    private int id;
    private int workspaceId;
    private int approvalId;
    private int stepId;
    private String decision;
    private int decidedBy;
    private String comment;
    private String decidedAt;
}
