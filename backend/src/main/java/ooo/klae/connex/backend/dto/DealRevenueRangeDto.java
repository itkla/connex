package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/** UTC timestamp range containing realized revenue events that require timezone bucketing. */
public record DealRevenueRangeDto(LocalDateTime earliest, LocalDateTime latest) {}
