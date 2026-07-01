package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A computed risk assessment for a single open deal.
 *
 * <p>Derived on read from the deal's timeline, expected close date, and the warmth of its
 * stakeholders (see {@link ooo.klae.connex.backend.services.DealRiskService}); it is never
 * persisted. The overall {@link #level} is the highest severity among the {@link #factors} that
 * fired, and {@link #score} is a bounded composite used only to order at-risk deals.
 *
 * @see DealRiskFactor
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DealRiskDto {
    /** Id of the assessed deal. */
    private int dealId;
    /** Overall risk band: {@code "high" | "medium" | "low" | "none"}. */
    private String level;
    /** Bounded 0–100 composite score used to order at-risk deals; {@code 0} when not at risk. */
    private int score;
    /** The signals that fired, ordered by descending severity; empty when {@link #level} is {@code "none"}. */
    private List<DealRiskFactor> factors;
    /** UTC {@code yyyy-MM-dd HH:mm:ss} time the assessment was computed. */
    private String assessedAt;
}
