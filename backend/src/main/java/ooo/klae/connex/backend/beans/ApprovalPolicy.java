package ooo.klae.connex.backend.beans;

import java.math.BigDecimal;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Declares when a generated deal document requires internal approval before it can be finalized.
 * A policy matches a document when its type filter passes and, if any threshold is configured, at
 * least one threshold is met; a policy with no thresholds matches every document of its type.
 * {@code minTotal} is currency-explicit and never compared across currencies.
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
    private String createdAt;
    private String updatedAt;
}
