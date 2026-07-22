package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Create or replace payload for a campaign.
 * @param name campaign name
 * @param objective optional campaign objective
 * @param type campaign type token
 * @param status optional lifecycle status; defaults to draft on create
 * @param ownerUserId optional active workspace member owner
 * @param budgetAmount optional non-negative budget
 * @param budgetCurrency optional ISO-4217 currency paired with the budget
 * @param startAt optional campaign start
 * @param endAt optional campaign end
 * @param parentCampaignId optional parent program campaign
 */
public record CampaignRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 255) String objective,
        @NotBlank @Size(max = 32) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,31}") String type,
        @Pattern(regexp = "draft|scheduled|active|paused|completed|archived") String status,
        @Positive Integer ownerUserId,
        @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal budgetAmount,
        @Pattern(regexp = "[A-Za-z]{3}") String budgetCurrency,
        LocalDateTime startAt,
        LocalDateTime endAt,
        @Positive Integer parentCampaignId) {
}
