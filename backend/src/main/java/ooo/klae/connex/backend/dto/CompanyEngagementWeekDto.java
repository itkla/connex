package ooo.klae.connex.backend.dto;

/** One of twelve bounded company engagement points rendered by the company card. */
public record CompanyEngagementWeekDto(
    long weekStart,
    long count,
    long activities,
    long tasks,
    long notes
) {}
