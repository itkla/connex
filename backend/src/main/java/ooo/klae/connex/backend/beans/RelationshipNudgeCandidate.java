package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Workspace-scoped projection of a deal stakeholder who may warrant a relationship-decay nudge.
 *
 * <p>One row per (open deal, stakeholder contact, recipient) tuple, where the recipient is the
 * deal's owner or one of its collaborators. The decay decision itself is layered on at
 * reconciliation time from the contact's computed warmth, so this projection carries only the
 * deal/contact/recipient identity and labels — not the temperature.
 *
 * <p>The deal-importance fields ({@code dealValue}, the stage position, {@code personRole}) feed
 * the nudge's priority weighting: a decaying relationship on a high-value, late-stage, or
 * soon-closing deal — or with a key stakeholder — is escalated over an otherwise-equal one.
 */
@Data
@NoArgsConstructor
public class RelationshipNudgeCandidate {
    private int workspaceId;
    private int dealId;
    private String dealLabel;
    private String expectedCloseDate;
    private double dealValue;
    private Integer stagePosition;
    private Integer pipelineMaxPosition;
    private int personId;
    private String personLabel;
    private String personRole;
    private int recipientId;
}
