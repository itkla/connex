package ooo.klae.connex.backend.dto;

/**
 * Viewer-authorized record identity for one assistant answer handle.
 *
 * @param handle per-turn resource handle the answer cited
 * @param kind record kind
 * @param id record identifier
 * @param label projected record name, empty when the record has no name
 * @param asOf ISO-8601 instant the record was last updated, or null when unknown
 * @param detail short bounded subtitle such as the company of a person, or null
 * @param observed whether {@code asOf} and {@code detail} are the values the answering turn
 *     recorded rather than the record's current state, which a message stored before turn-time
 *     evidence snapshots existed cannot supply
 */
public record AiChatCitationDto(
        String handle,
        String kind,
        int id,
        String label,
        String asOf,
        String detail,
        boolean observed) {

    /** Creates a citation with a projected label but no freshness or subtitle. */
    public AiChatCitationDto(String handle, String kind, int id, String label) {
        this(handle, kind, id, label, null, null, false);
    }
}
