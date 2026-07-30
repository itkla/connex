package ooo.klae.connex.backend.beans;

import lombok.Data;

/**
 * Existing interaction-history provenance key and its canonical payload hash.
 */
@Data
public class HistoryImportProvenance {
    private int entityId;
    private String historyImportKey;
    private String historyPayloadHash;
}
