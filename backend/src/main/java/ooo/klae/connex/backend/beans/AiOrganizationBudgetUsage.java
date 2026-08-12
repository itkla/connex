package ooo.klae.connex.backend.beans;

import lombok.Data;

/** Locked organization token usage for one UTC day. */
@Data
public class AiOrganizationBudgetUsage {
    private int orgId;
    private long consumedTokens;
}
