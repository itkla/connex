package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Reader-visible note identifier projected for a deal without loading private note content.
 */
@Data
@NoArgsConstructor
public class DealNoteId {
    private int dealId;
    private int noteId;
}
