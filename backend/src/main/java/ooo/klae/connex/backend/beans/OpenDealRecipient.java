package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Workspace-scoped projection of an open deal and one of its notification recipients (its owner or a
 * collaborator, always a current workspace member). One row per (open deal, recipient); the deal-risk
 * reconciliation pass joins these against the computed risk to emit per-recipient notifications.
 */
@Data
@NoArgsConstructor
public class OpenDealRecipient {
    private int dealId;
    private String dealLabel;
    private int recipientId;
}
