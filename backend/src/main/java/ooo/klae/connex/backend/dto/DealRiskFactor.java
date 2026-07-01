package ooo.klae.connex.backend.dto;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single deterministic risk signal contributing to a {@link DealRiskDto}.
 *
 * <p>Each factor is one reason a deal looks at-risk — a passed close date, a deal that has gone
 * quiet, a key stakeholder cooling off. The {@link #code} is a stable machine identifier the
 * frontend maps to localized copy; {@link #params} carries the structured detail (day counts,
 * stakeholder identity) that copy interpolates, so the wording lives on the client and stays
 * translatable rather than being baked into the API.
 *
 * @see ooo.klae.connex.backend.services.DealRiskService
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DealRiskFactor {
    /**
     * Stable signal code, one of
     * {@code close_overdue | closing_soon_quiet | stalled | stakeholder_cold | no_stakeholders}.
     */
    private String code;
    /** Contribution severity: {@code "high" | "medium" | "low"}. */
    private String severity;
    /** Structured detail for rendering the localized message (e.g. {@code daysOverdue}, {@code person}). */
    private Map<String, Object> params;
}
