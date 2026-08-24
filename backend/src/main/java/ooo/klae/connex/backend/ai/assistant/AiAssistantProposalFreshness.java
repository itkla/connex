package ooo.klae.connex.backend.ai.assistant;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Whether a record moved between a proposal being made and being applied.
 *
 * <p>One rule, because two would eventually disagree: the card that decides whether to warn the
 * member and the approval that decides whether to refuse the write have to mean the same thing by
 * "this record changed". A card reading "ready" over a write that then refuses, or a warning over a
 * write that goes through anyway, is the interface lying about the state of the workspace.
 *
 * <p>The two timestamps do not carry the same precision. A record's {@code updated_at} is stored to
 * the second while a tool call's {@code created_at} keeps microseconds, so an edit made in the same
 * clock second as the proposal reads as the earlier of the two however it is compared. The
 * comparison is therefore made at second resolution and the boundary is treated as a change: a
 * record written in the second the proposal was recorded refuses rather than overwrites, because
 * the stored timestamps cannot say which of the two happened first.
 *
 * <p>That deliberately costs a false positive. A proposal recorded in the same second as its
 * target's last edit — including one the assistant itself made moments earlier — is reported as
 * changed and refused, and the member asks for it again against the current values. An ambiguous
 * second that silently overwrote a colleague's edit would cost more.
 *
 * <p>A timestamp neither side can read is never reported as a change. Claiming a record moved when
 * nothing established that would hold back a change that is perfectly applicable.
 */
final class AiAssistantProposalFreshness {

    private AiAssistantProposalFreshness() {
    }

    /**
     * Whether the record was written after the proposal was made, or in the same second as it.
     *
     * @param recordUpdatedAt the record's stored update timestamp
     * @param proposedAt when the proposal was recorded
     * @return true only when both timestamps are readable and the record's is not strictly earlier
     *     than the second the proposal was recorded in
     */
    static boolean changedSince(String recordUpdatedAt, String proposedAt) {
        LocalDateTime updated = storedTimestamp(recordUpdatedAt);
        LocalDateTime proposed = storedTimestamp(proposedAt);
        return updated != null
                && proposed != null
                && !updated.isBefore(proposed.truncatedTo(ChronoUnit.SECONDS));
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
