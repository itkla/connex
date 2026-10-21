package ooo.klae.connex.backend.dto;

import java.util.List;

/** Truthful independently committed review outcomes, including an unreconciled uncertain commit. */
public record AiAgentProposalDecisionResponse(boolean complete, String stopReason, List<Item> items,
        String stateVersion, String outputRevision, boolean contentWithheld) {
    /** A canonical terminal result, refusal, unprocessed selection, or indeterminate commit. */
    public record Item(String actionSlot, String outcome, String code) {
    }
}
