package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Workspace-scoped deal reminder projection.
 */
@Data
@NoArgsConstructor
public class DealReminderCandidate {
    private int workspaceId;
    private int dealId;
    private String dealLabel;
    private String expectedCloseDate;
    private int recipientId;
    private String recipientTimezone;
}