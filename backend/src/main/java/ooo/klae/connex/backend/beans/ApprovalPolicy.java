package ooo.klae.connex.backend.beans;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Declares when a generated deal document requires internal approval before it can be finalized.
 * A policy matches a document when its type filter passes and, if any threshold is configured, at
 * least one threshold is met; a policy with no thresholds matches every document of its type.
 * {@code minTotal} is currency-explicit and never compared across currencies.
 *
 * <p>The chain describes who must approve: {@code mode} runs the steps one at a time
 * ({@code sequential}) or all at once ({@code parallel}), and {@code separationOfDuties} decides
 * whether the requester and the document's author may decide. A policy with no steps behaves like
 * one step requiring one approval from any member holding {@code DOCUMENT_APPROVE}.
 */
@Data
@NoArgsConstructor
public class ApprovalPolicy {
    private int id;
    private int workspaceId;
    private String name;
    private boolean active;
    private String documentType;
    private String currency;
    private BigDecimal minTotal;
    private BigDecimal minDiscountPercent;
    private String mode;
    private String separationOfDuties;
    private String createdAt;
    private String updatedAt;
    private List<ApprovalPolicyStep> steps = new ArrayList<>();
}
