package ooo.klae.connex.backend.dto;

import java.util.List;

/** One coherent relationship-insights snapshot for the dashboard. */
public record RelationshipDashboardDto(
    WarmthSummaryDto warmthSummary,
    List<DashboardContactTemperatureDto> coolingContacts,
    List<DashboardCompanyTemperatureDto> coolingCompanies,
    List<DashboardDealRiskDto> dealRisks
) {}
