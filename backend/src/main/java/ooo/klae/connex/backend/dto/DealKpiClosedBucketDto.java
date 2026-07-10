package ooo.klae.connex.backend.dto;

/**
 * Mapper projection for closed-deal KPIs in one trend bucket.
 */
public record DealKpiClosedBucketDto(
    int bucketIndex,
    double wonValue,
    long wonCount,
    long lostCount,
    double avgCycleDays
) {}
