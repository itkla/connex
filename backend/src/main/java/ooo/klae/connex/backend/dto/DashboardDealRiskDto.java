package ooo.klae.connex.backend.dto;

/** Lean deal and optional company paired with a dashboard risk assessment. */
public record DashboardDealRiskDto(
    DealDto deal,
    CompanyDto company,
    DealRiskDto risk
) {}
