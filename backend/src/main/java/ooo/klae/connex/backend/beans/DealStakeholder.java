package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Workspace-scoped projection of one deal stakeholder: which contact is on which deal, and in
 * what role. Bulk-loaded for deal-risk scoring so a whole workspace's stakeholders resolve in a
 * single query rather than one per deal.
 *
 * @see ooo.klae.connex.backend.services.DealRiskService
 */
@Data
@NoArgsConstructor
public class DealStakeholder {
    private int dealId;
    private int personId;
    private String personLabel;
    private String role;
}
