package ooo.klae.connex.backend.dto;

import java.util.List;

/** Personal risks and their bounded display records; capacity refuses an incomplete ranking. */
public record MyWorkRisksDto(List<DealRiskDto> risks, List<DealDto> deals, boolean inputLimitExceeded) {}
