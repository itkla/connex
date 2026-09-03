package ooo.klae.connex.backend.dto.sequence;

/**
 * Allowlisted merge field available to sequence content.
 *
 * @param key token key used as {@code {{key}}}
 * @param category owning record category
 * @param label human-readable field label
 */
public record SequenceMergeFieldDto(String key, String category, String label) {
}
