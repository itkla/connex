package ooo.klae.connex.backend.beans;

import lombok.Data;

/** Organization daily token limit stored on the control plane. */
@Data
public class AiOrganizationBudget {
    private int orgId;
    private long dailyTokenLimit;
}
