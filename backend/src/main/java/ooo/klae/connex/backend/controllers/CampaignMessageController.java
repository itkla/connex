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

import ooo.klae.connex.backend.dto.CampaignMessageDto;
import ooo.klae.connex.backend.dto.CampaignMessageRequest;
import ooo.klae.connex.backend.dto.CampaignMessageRevisionRequest;
import ooo.klae.connex.backend.services.CampaignSendService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Workspace-scoped campaign message and revision endpoints. */
@RestController
@RequestMapping("/api/campaigns/{id}/messages")
@RequiredArgsConstructor
@Validated
public class CampaignMessageController {
    private final CampaignSendService campaignSendService;

    @GetMapping
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public List<CampaignMessageDto> list(@Positive @PathVariable int id) {
        return campaignSendService.listMessages(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.CAMPAIGN_MANAGE)
    public CampaignMessageDto create(
            @Positive @PathVariable int id,
            @Valid @RequestBody CampaignMessageRequest request) {
        return campaignSendService.createMessage(id, request);
    }

    @GetMapping("/{messageId}")
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public CampaignMessageDto get(
            @Positive @PathVariable int id,
            @Positive @PathVariable int messageId) {
        return campaignSendService.getMessage(id, messageId);
    }

    @PostMapping("/{messageId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.CAMPAIGN_MANAGE)
    public CampaignMessageDto addRevision(
            @Positive @PathVariable int id,
            @Positive @PathVariable int messageId,
            @Valid @RequestBody CampaignMessageRevisionRequest request) {
        return campaignSendService.addRevision(id, messageId, request);
    }
}
