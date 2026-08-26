package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.CampaignEngagementDto;
import ooo.klae.connex.backend.dto.CampaignRecipientDto;
import ooo.klae.connex.backend.dto.CampaignSendEngagementDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.services.CampaignEngagementService;
import ooo.klae.connex.backend.services.CampaignRecipientService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import ooo.klae.connex.backend.util.DealFilterNormalizer;
import ooo.klae.connex.backend.util.PageBounds;

/** Workspace-scoped read-only campaign engagement and attribution endpoints. */
@RestController
@RequestMapping("/api/campaigns/{id}")
@RequiredArgsConstructor
@Validated
public class CampaignEngagementController {
    private final CampaignEngagementService campaignEngagementService;
    private final CampaignRecipientService campaignRecipientService;

    @GetMapping("/engagement")
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public CampaignEngagementDto engagement(@Positive @PathVariable int id) {
        return campaignEngagementService.getCampaignEngagement(id);
    }

    @GetMapping("/sends/{sendId}/engagement")
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public CampaignSendEngagementDto sendEngagement(
            @Positive @PathVariable int id,
            @Positive @PathVariable int sendId) {
        return campaignEngagementService.getSendEngagement(id, sendId);
    }

    /**
     * Returns the recipients behind a campaign's engagement counts, as contact record links.
     *
     * <p>Filtering by {@code status} selects the population of a status-derived counter; filtering
     * by {@code event} selects an event-derived one, which is how {@code unsubscribed} is reached.
     *
     * @param id the campaign id
     * @param sendId one send to restrict to, or null for every send
     * @param status optional delivery statuses to include
     * @param event an optional lifecycle event the delivery must carry
     * @param page the one-based page number
     * @param size the page size, capped by {@link PageBounds#MAX_SIZE}
     * @return the page of recipients and the total it was drawn from
     */
    @GetMapping("/recipients")
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public PageResponse<CampaignRecipientDto> recipients(
            @Positive @PathVariable int id,
            @RequestParam(required = false) @Positive Integer sendId,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String event,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int size) {
        PageBounds bounds = PageBounds.of(page, size);
        return campaignRecipientService.getRecipients(
            id,
            sendId,
            DealFilterNormalizer.normalizeValues(
                status, CampaignRecipientService.RECIPIENT_STATUSES, "status"),
            DealFilterNormalizer.validateOptionalValue(
                event, CampaignRecipientService.RECIPIENT_EVENTS, "event"),
            bounds.size(),
            bounds.offset());
    }
}
