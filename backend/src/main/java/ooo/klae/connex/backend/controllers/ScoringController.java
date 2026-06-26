package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.services.ScoringService;
import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Read-only relationship-temperature (warmth) scores for the active workspace.
 * Computed on read; see {@link ScoringService}.
 */
@RestController
@RequestMapping("/api/scoring")
@RequiredArgsConstructor
public class ScoringController {
    private final ScoringService scoringService;
    private final WorkspaceService workspaceService;

    /** Warmth for every contact in the active workspace. */
    @GetMapping("/contacts")
    public List<RelationshipTemperatureDto> contacts() {
        return scoringService.scoreContacts(workspaceService.getCurrentWorkspaceId());
    }

    /** Warmth for every company in the active workspace. */
    @GetMapping("/companies")
    public List<RelationshipTemperatureDto> companies() {
        return scoringService.scoreCompanies(workspaceService.getCurrentWorkspaceId());
    }
}
