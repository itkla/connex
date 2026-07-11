package ooo.klae.connex.backend.dto;

/**
 * Mapper projection for a numeric deal total in one trend bucket.
 */
public record DealBucketValueDto(
    int bucketIndex,
    double value
) {}
