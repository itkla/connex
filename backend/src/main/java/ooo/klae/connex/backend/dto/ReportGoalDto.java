package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Workspace-scoped report goal with a control-plane-hydrated owner label.
 * @param id goal id
 * @param ownerId optional owner scope; null means workspace-wide
 * @param ownerLabel current workspace member display name, when available
 * @param metric goal metric
 * @param periodType month or quarter
 * @param periodStart canonical period start
 * @param targetValue target revenue
 * @param currency revenue currency
 * @param createdBy creator user id
 * @param createdAt creation timestamp
 * @param updatedAt update timestamp
 */
public record ReportGoalDto(
        int id,
        Integer ownerId,
        String ownerLabel,
        String metric,
        String periodType,
        LocalDate periodStart,
        BigDecimal targetValue,
        String currency,
        Integer createdBy,
        String createdAt,
        String updatedAt) {
}
