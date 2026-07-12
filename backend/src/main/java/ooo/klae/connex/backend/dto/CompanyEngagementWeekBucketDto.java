package ooo.klae.connex.backend.dto;

/** SQL-aggregated engagement counts for one zero-based week bucket. */
public record CompanyEngagementWeekBucketDto(
    int bucketIndex,
    long activities,
    long tasks,
    long notes
) {}
