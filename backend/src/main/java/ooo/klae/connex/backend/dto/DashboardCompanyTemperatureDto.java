package ooo.klae.connex.backend.dto;

/** Lean company record paired with its dashboard relationship temperature. */
public record DashboardCompanyTemperatureDto(
    CompanyDto company,
    RelationshipTemperatureDto temperature
) {}
