package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.CampaignAudienceDto;
import ooo.klae.connex.backend.dto.CampaignAudienceEstimateDto;
import ooo.klae.connex.backend.dto.CampaignAudienceRequest;
import ooo.klae.connex.backend.dto.CampaignAudienceSnapshotDto;
import ooo.klae.connex.backend.dto.CampaignAudienceSnapshotSummaryDto;
import ooo.klae.connex.backend.dto.CampaignDto;
import ooo.klae.connex.backend.dto.CampaignRequest;
import ooo.klae.connex.backend.services.CampaignService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Workspace-scoped campaign and reproducible audience snapshot endpoints. */
@RestController
@RequestMapping("/api/campaigns")
@RequiredArgsConstructor
@Validated
public class CampaignController {
    private final CampaignService campaignService;

    @GetMapping
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public List<CampaignDto> list() {
        return campaignService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.CAMPAIGN_MANAGE)
    public CampaignDto create(@Valid @RequestBody CampaignRequest request) {
        return campaignService.create(request);
    }

    @GetMapping("/{id}")
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public CampaignDto get(@Positive @PathVariable int id) {
        return campaignService.get(id);
    }

    @PutMapping("/{id}")
    @RequirePermission(Permission.CAMPAIGN_MANAGE)
    public CampaignDto update(
            @Positive @PathVariable int id,
            @Valid @RequestBody CampaignRequest request) {
        return campaignService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(Permission.CAMPAIGN_MANAGE)
    public void delete(@Positive @PathVariable int id) {
        campaignService.delete(id);
    }

    @PutMapping("/{id}/audience")
    @RequirePermission(Permission.CAMPAIGN_MANAGE)
    public CampaignAudienceDto setAudience(
            @Positive @PathVariable int id,
            @Valid @RequestBody CampaignAudienceRequest request) {
        return campaignService.setAudience(id, request);
    }

    @PostMapping("/{id}/audience/estimate")
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public CampaignAudienceEstimateDto estimateAudience(@Positive @PathVariable int id) {
        return campaignService.estimateAudience(id);
    }

    @PostMapping("/{id}/audience/snapshot")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.CAMPAIGN_MANAGE)
    public CampaignAudienceSnapshotDto snapshotAudience(@Positive @PathVariable int id) {
        return campaignService.snapshotAudience(id);
    }

    @GetMapping("/{id}/audience/snapshots")
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public List<CampaignAudienceSnapshotSummaryDto> listSnapshots(@Positive @PathVariable int id) {
        return campaignService.listSnapshots(id);
    }

    @GetMapping("/{id}/audience/snapshots/{version}")
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public CampaignAudienceSnapshotDto getSnapshot(
            @Positive @PathVariable int id,
            @Positive @PathVariable int version) {
        return campaignService.getSnapshot(id, version);
    }
}
