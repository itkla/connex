package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.CampaignSendDto;
import ooo.klae.connex.backend.dto.CampaignSendRequest;
import ooo.klae.connex.backend.services.CampaignSendService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Workspace-scoped campaign send lifecycle endpoints. */
@RestController
@RequestMapping("/api/campaigns/{id}/sends")
@RequiredArgsConstructor
@Validated
public class CampaignSendController {
    private final CampaignSendService campaignSendService;

    @GetMapping
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public List<CampaignSendDto> list(@Positive @PathVariable int id) {
        return campaignSendService.listSends(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.CAMPAIGN_MANAGE)
    public CampaignSendDto create(
            @Positive @PathVariable int id,
            @Valid @RequestBody CampaignSendRequest request) {
        return campaignSendService.createSend(id, request);
    }

    @GetMapping("/{sendId}")
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public CampaignSendDto get(
            @Positive @PathVariable int id,
            @Positive @PathVariable int sendId) {
        return campaignSendService.getSend(id, sendId);
    }

    @PostMapping("/{sendId}/queue")
    @RequirePermission(Permission.CAMPAIGN_SEND)
    public CampaignSendDto queue(
            @Positive @PathVariable int id,
            @Positive @PathVariable int sendId) {
        return campaignSendService.queueSend(id, sendId);
    }

    @PostMapping("/{sendId}/pause")
    @RequirePermission(Permission.CAMPAIGN_SEND)
    public CampaignSendDto pause(
            @Positive @PathVariable int id,
            @Positive @PathVariable int sendId) {
        return campaignSendService.pauseSend(id, sendId);
    }

    @PostMapping("/{sendId}/cancel")
    @RequirePermission(Permission.CAMPAIGN_SEND)
    public CampaignSendDto cancel(
            @Positive @PathVariable int id,
            @Positive @PathVariable int sendId) {
        return campaignSendService.cancelSend(id, sendId);
    }
}
