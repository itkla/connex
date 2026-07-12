package ooo.klae.connex.backend.dto;

/** Lean contact record paired with its dashboard relationship temperature. */
public record DashboardContactTemperatureDto(
    PersonDto contact,
    RelationshipTemperatureDto temperature
) {}
