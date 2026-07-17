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

import ooo.klae.connex.backend.dto.CampaignAudienceExportDto;
import ooo.klae.connex.backend.dto.CampaignAudienceExportRequest;
import ooo.klae.connex.backend.services.AudienceExportService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Workspace-scoped campaign audience-export endpoints. */
@RestController
@RequestMapping("/api/campaigns/{id}/exports")
@RequiredArgsConstructor
@Validated
public class CampaignExportController {

    private final AudienceExportService audienceExportService;

    @GetMapping
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public List<CampaignAudienceExportDto> list(@Positive @PathVariable int id) {
        return audienceExportService.listExports(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.CAMPAIGN_MANAGE)
    public CampaignAudienceExportDto create(
            @Positive @PathVariable int id,
            @Valid @RequestBody CampaignAudienceExportRequest request) {
        return audienceExportService.createExport(id, request);
    }

    @GetMapping("/{exportId}")
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public CampaignAudienceExportDto get(
            @Positive @PathVariable int id,
            @Positive @PathVariable int exportId) {
        return audienceExportService.getExport(id, exportId);
    }
}
