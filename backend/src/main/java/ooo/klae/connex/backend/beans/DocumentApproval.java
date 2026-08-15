package ooo.klae.connex.backend.beans;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One approval request on an immutable generated deal document, including its decision once made.
 * A document has at most one pending approval at a time; history rows are kept for auditability.
 * The chain is frozen onto the request as {@link DocumentApprovalStep} rows, and {@code decidedBy}
 * / {@code decisionComment} record the decision that terminated the whole request.
 */
@Data
@NoArgsConstructor
public class DocumentApproval {
    private int id;
    private int workspaceId;
    private int dealId;
    private int documentId;
    private Integer policyId;
    private String status;
    private String outcomeReason;
    private String outcomeDetail;
    private String mode;
    private String separationOfDuties;
    private Integer requestedBy;
    private String requestComment;
    private Integer decidedBy;
    private String decisionComment;
    private String decidedAt;
    private String createdAt;
    private String updatedAt;
    private List<DocumentApprovalStep> steps = new ArrayList<>();
}
