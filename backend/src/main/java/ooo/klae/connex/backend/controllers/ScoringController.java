package ooo.klae.connex.backend.controllers;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.dto.WarmthSummaryDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.ScoringService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.util.PageBounds;

/**
 * Read-only relationship-temperature scores and workspace-wide warmth summaries.
 * Computed on read; see {@link ScoringService}.
 */
@RestController
@RequestMapping("/api/scoring")
@RequiredArgsConstructor
public class ScoringController {
    private final ScoringService scoringService;
    private final WorkspaceService workspaceService;

    /** Warmth for up to {@link PageBounds#MAX_SIZE} visible contacts in the active workspace. */
    @GetMapping("/contacts")
    public List<RelationshipTemperatureDto> contacts(@RequestParam(required = false) List<Integer> ids) {
        return scoringService.scoreContacts(workspaceService.getCurrentWorkspaceId(), boundedIds(ids));
    }

    /** Highest-priority cooling contacts for dashboard cards. */
    @GetMapping("/contacts/cooling")
    public List<RelationshipTemperatureDto> coolingContacts(
            @RequestParam(defaultValue = "6") int limit) {
        return scoringService.coolingContacts(
            workspaceService.getCurrentWorkspaceId(), boundedLimit(limit));
    }

    /** Warmth for up to {@link PageBounds#MAX_SIZE} visible companies in the active workspace. */
    @GetMapping("/companies")
    public List<RelationshipTemperatureDto> companies(@RequestParam(required = false) List<Integer> ids) {
        return scoringService.scoreCompanies(workspaceService.getCurrentWorkspaceId(), boundedIds(ids));
    }

    /** Highest-priority cooling companies for dashboard cards. */
    @GetMapping("/companies/cooling")
    public List<RelationshipTemperatureDto> coolingCompanies(
            @RequestParam(defaultValue = "6") int limit) {
        return scoringService.coolingCompanies(
            workspaceService.getCurrentWorkspaceId(), boundedLimit(limit));
    }

    /** Workspace-wide warmth bands, contact trends, and predicted contact decay counts. */
    @GetMapping("/summary")
    public WarmthSummaryDto summary() {
        return scoringService.summarize(workspaceService.getCurrentWorkspaceId());
    }

    private Set<Integer> boundedIds(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BadRequestException("ids are required for relationship temperature scoring");
        }
        if (ids.size() > PageBounds.MAX_SIZE) {
            throw new BadRequestException("At most " + PageBounds.MAX_SIZE + " ids may be scored at once");
        }
        Set<Integer> out = new LinkedHashSet<>();
        for (Integer id : ids) {
            if (id == null || id < 1) {
                throw new BadRequestException("ids must be positive integers");
            }
            out.add(id);
        }
        return out;
    }

    private int boundedLimit(int limit) {
        if (limit < 1 || limit > PageBounds.MAX_SIZE) {
            throw new BadRequestException("limit must be between 1 and " + PageBounds.MAX_SIZE);
        }
        return limit;
    }
}
