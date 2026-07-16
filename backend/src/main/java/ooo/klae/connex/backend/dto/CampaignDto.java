package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * API representation of a workspace-scoped campaign.
 * @param id campaign id
 * @param name campaign name
 * @param objective optional objective
 * @param type campaign type
 * @param status lifecycle status
 * @param ownerUserId optional owner member id
 * @param budgetAmount optional budget amount
 * @param budgetCurrency optional budget currency
 * @param startAt optional start
 * @param endAt optional end
 * @param parentCampaignId optional parent campaign
 * @param createdById creator id
 * @param createdAt creation timestamp
 * @param updatedAt update timestamp
 */
public record CampaignDto(
        int id,
        String name,
        String objective,
        String type,
        String status,
        Integer ownerUserId,
        BigDecimal budgetAmount,
        String budgetCurrency,
        LocalDateTime startAt,
        LocalDateTime endAt,
        Integer parentCampaignId,
        Integer createdById,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
