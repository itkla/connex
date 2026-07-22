package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.RelationshipDashboardDto;
import ooo.klae.connex.backend.services.RelationshipDashboardService;
import ooo.klae.connex.backend.services.WorkspaceService;

/** Read-only coherent relationship snapshot for dashboard widgets. */
@RestController
@RequestMapping("/api/scoring/dashboard")
@RequiredArgsConstructor
public class RelationshipDashboardController {
    private final RelationshipDashboardService dashboardService;
    private final WorkspaceService workspaceService;

    /** Returns warmth, cooling records, and deal risk from one shared score pass. */
    @GetMapping
    public RelationshipDashboardDto dashboard() {
        return dashboardService.getDashboard(workspaceService.getCurrentWorkspaceId());
    }
}
