package ooo.klae.connex.backend.dto;

/**
 * Activity counts by type for one analytics time bucket.
 */
public record ActivityVolumeBucketDto(
    int bucketIndex,
    long call,
    long email,
    long meeting,
    long note,
    long other
) {}
