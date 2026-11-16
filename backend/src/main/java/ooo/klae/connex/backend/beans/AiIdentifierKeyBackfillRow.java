package ooo.klae.connex.backend.beans;

import lombok.Getter;
import lombok.Setter;

/** One examined owned row; even current or restricted rows advance the reconciliation cursor. */
@Getter
@Setter
public class AiIdentifierKeyBackfillRow {
    private String kind;
    private int id;
    private String value;
    private boolean needsBackfill;
}
