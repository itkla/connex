package ooo.klae.connex.backend.dto;

/** One daily organization AI usage aggregate by feature and requesting member. */
public record AiUsageBreakdownDto(
        Integer userId,
        String displayName,
        String feature,
        long inputUsage,
        long outputUsage) {
}
