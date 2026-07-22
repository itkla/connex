package ooo.klae.connex.backend.dto;

/** Lifetime company engagement counts with private notes excluded. */
public record CompanyEngagementCountsDto(
    long personCount,
    long numDeals,
    long numTasks,
    long openTasks,
    long numActivities,
    long numNotes
) {}
