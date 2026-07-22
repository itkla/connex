package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One approval request on an immutable generated deal document, including its decision once made.
 * A document has at most one pending approval at a time; history rows are kept for auditability.
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
    private Integer requestedBy;
    private String requestComment;
    private Integer decidedBy;
    private String decisionComment;
    private String decidedAt;
    private String createdAt;
    private String updatedAt;
}
