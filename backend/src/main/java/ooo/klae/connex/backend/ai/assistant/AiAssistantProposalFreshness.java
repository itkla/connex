package ooo.klae.connex.backend.ai.assistant;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * Whether a record moved between a proposal being made and being applied.
 *
 * <p>One rule, because two would eventually disagree: the card that decides whether to warn the
 * member and the approval that decides whether to refuse the write have to mean the same thing by
 * "this record changed". A card reading "ready" over a write that then refuses, or a warning over a
 * write that goes through anyway, is the interface lying about the state of the workspace.
 *
 * <p>A timestamp neither side can read is never reported as a change. Claiming a record moved when
 * nothing established that would hold back a change that is perfectly applicable.
 */
final class AiAssistantProposalFreshness {

    private AiAssistantProposalFreshness() {
    }

    /**
     * Whether the record was written after the proposal was made.
     *
     * @param recordUpdatedAt the record's stored update timestamp
     * @param proposedAt when the proposal was recorded
     * @return true only when both timestamps are readable and the record's is the later one
     */
    static boolean changedSince(String recordUpdatedAt, String proposedAt) {
        LocalDateTime updated = storedTimestamp(recordUpdatedAt);
        LocalDateTime proposed = storedTimestamp(proposedAt);
        return updated != null && proposed != null && updated.isAfter(proposed);
    }

    private static LocalDateTime storedTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.strip().replace(' ', 'T'));
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
