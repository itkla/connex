package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Per-row interaction-history preview decision.
 *
 * @param rowIndex zero-based source row index
 * @param status ready, already_imported, needs_review, or invalid
 * @param participantId resolved owned contact id
 * @param participantLabel resolved owned contact label
 * @param candidates bounded visible duplicate candidates
 * @param errors validation or review reasons
 */
public record HistoryImportRowAnalysis(
        int rowIndex,
        String status,
        Integer participantId,
        String participantLabel,
        List<DuplicateCandidateDto> candidates,
        List<String> errors) {

    public HistoryImportRowAnalysis {
        candidates = List.copyOf(candidates);
        errors = List.copyOf(errors);
    }
}
