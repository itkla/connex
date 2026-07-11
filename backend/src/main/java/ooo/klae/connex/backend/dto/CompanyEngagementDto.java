package ooo.klae.connex.backend.dto;

import java.util.List;

/** Complete company-scoped inputs for one expanded company card. */
public record CompanyEngagementDto(
    List<PersonDto> persons,
    List<DealDto> deals,
    List<CompanyEngagementTouchDto> touches
) {}
