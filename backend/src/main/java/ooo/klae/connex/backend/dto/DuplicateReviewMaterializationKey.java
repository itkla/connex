package ooo.klae.connex.backend.dto;

/**
 * Server-only key used to materialize one page of duplicate-review evidence groups.
 *
 * @param recordType person or company
 * @param kind canonical identity kind
 * @param normalizedValue canonical identity value
 * @param evidenceFingerprint non-PII stable evidence fingerprint
 */
public record DuplicateReviewMaterializationKey(
        String recordType,
        String kind,
        String normalizedValue,
        String evidenceFingerprint) {
}
