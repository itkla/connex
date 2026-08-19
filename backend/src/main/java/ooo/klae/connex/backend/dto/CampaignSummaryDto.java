package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/**
 * Bounded campaign projection for a global-search result row.
 *
 * <p>Excludes the budget and hierarchy columns the full {@link CampaignDto} carries; a search row
 * renders the name plus a type/status qualifier.
 *
 * @param id the campaign id
 * @param name the campaign name
 * @param type the campaign type
 * @param status the lifecycle status
 * @param startAt the optional start
 * @param endAt the optional end
 * @param updatedAt when the campaign last changed
 */
public record CampaignSummaryDto(
        int id,
        String name,
        String type,
        String status,
        LocalDateTime startAt,
        LocalDateTime endAt,
        LocalDateTime updatedAt) {
}
