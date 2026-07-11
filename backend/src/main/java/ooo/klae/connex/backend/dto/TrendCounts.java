package ooo.klae.connex.backend.dto;

/**
 * Relationship counts by warmth trend.
 */
public record TrendCounts(long rising, long steady, long cooling) {}
