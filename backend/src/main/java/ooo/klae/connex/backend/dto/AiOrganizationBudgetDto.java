package ooo.klae.connex.backend.dto;

import java.time.LocalDate;
import java.util.List;

/** Organization daily token budget, explicit exhaustion state, and usage breakdown. */
public record AiOrganizationBudgetDto(
        int orgId,
        LocalDate usageDay,
        long dailyUsageLimit,
        long consumedUsage,
        long reservedUsage,
        long remainingUsage,
        boolean exhausted,
        List<AiUsageBreakdownDto> usage) {

    public AiOrganizationBudgetDto {
        usage = List.copyOf(usage);
    }
}
