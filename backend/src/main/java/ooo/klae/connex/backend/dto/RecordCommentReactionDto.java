package ooo.klae.connex.backend.dto;

import ooo.klae.connex.backend.beans.RecordCommentReactionSummary;

/** Aggregated reaction count and current-user state for one reaction key. */
public record RecordCommentReactionDto(String reaction, long count, boolean reactedByMe) {

    /** Maps a hydrated reaction summary into its API representation. */
    public static RecordCommentReactionDto from(RecordCommentReactionSummary summary) {
        return new RecordCommentReactionDto(
            summary.getReaction(), summary.getCount(), summary.isReactedByMe());
    }
}
