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
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.ScoringService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.util.PageBounds;

/**
 * Read-only relationship-temperature (warmth) scores for bounded active-workspace record sets.
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

    /** Warmth for up to {@link PageBounds#MAX_SIZE} visible companies in the active workspace. */
    @GetMapping("/companies")
    public List<RelationshipTemperatureDto> companies(@RequestParam(required = false) List<Integer> ids) {
        return scoringService.scoreCompanies(workspaceService.getCurrentWorkspaceId(), boundedIds(ids));
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
}
