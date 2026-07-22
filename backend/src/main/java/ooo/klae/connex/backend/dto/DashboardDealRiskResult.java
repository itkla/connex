package ooo.klae.connex.backend.dto;

import java.util.List;

/** Bounded dashboard risk items plus whether eligible deals remained unassessed. */
public record DashboardDealRiskResult(
    List<DealRiskDto> items,
    boolean truncated
) {}
