package ooo.klae.connex.backend.dto;

/** Non-PII state of the complete visible broad-name prefilter, including rows outside the display bound. */
public record DuplicateNameMatchState(long matchCount, long highestRecordId) {
}
