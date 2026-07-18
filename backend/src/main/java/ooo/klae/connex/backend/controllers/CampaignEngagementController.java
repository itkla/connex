package ooo.klae.connex.backend.controllers;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.CampaignEngagementDto;
import ooo.klae.connex.backend.dto.CampaignSendEngagementDto;
import ooo.klae.connex.backend.services.CampaignEngagementService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Workspace-scoped read-only campaign engagement and attribution endpoints. */
@RestController
@RequestMapping("/api/campaigns/{id}")
@RequiredArgsConstructor
@Validated
public class CampaignEngagementController {
    private final CampaignEngagementService campaignEngagementService;

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
}
