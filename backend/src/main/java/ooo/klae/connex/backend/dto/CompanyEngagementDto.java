package ooo.klae.connex.backend.dto;

import java.util.List;

/** Bounded, aggregate-only company engagement summary for one expanded company card. */
public record CompanyEngagementDto(
    List<CompanyEngagementPersonDto> persons,
    long personCount,
    List<Integer> relatedUserIds,
    long relatedUserCount,
    double pastRevenue,
    double projectedRevenue,
    String currency,
    long numDeals,
    long numTasks,
    long openTasks,
    long numActivities,
    long numNotes,
    List<CompanyEngagementWeekDto> weeklyEngagement
) {}
