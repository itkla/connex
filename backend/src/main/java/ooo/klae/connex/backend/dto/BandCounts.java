package ooo.klae.connex.backend.dto;

/**
 * Relationship counts by warmth band.
 */
public record BandCounts(long hot, long warm, long cool, long cold) {}
