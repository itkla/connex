package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Aggregated reaction state for one comment and reaction key. */
@Data
@NoArgsConstructor
public class RecordCommentReactionSummary {
    private long commentId;
    private String reaction;
    private long count;
    private boolean reactedByMe;
}
