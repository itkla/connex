package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.AiWorkspaceGovernanceDto;
import ooo.klae.connex.backend.dto.AiWorkspaceGovernanceRequest;
import ooo.klae.connex.backend.services.AiWorkspaceGovernanceService;
import ooo.klae.connex.backend.services.AuthService;

/** Organization-administrator controls for the active workspace's AI availability and turn cap. */
@RestController
@RequestMapping("/api/ai/governance")
@RequiredArgsConstructor
public class AiWorkspaceGovernanceController {
    private final AiWorkspaceGovernanceService governanceService;
    private final AuthService authService;

    /** Returns governance for the active workspace. */
    @GetMapping
    public AiWorkspaceGovernanceDto get(@RequestParam("workspaceId") int workspaceId) {
        return governanceService.getForWorkspace(
                workspaceId, authService.getCurrentUser().getId());
    }

    /** Replaces governance for the active workspace. */
    @PutMapping
    public AiWorkspaceGovernanceDto save(
            @RequestParam("workspaceId") int workspaceId,
            @Valid @RequestBody AiWorkspaceGovernanceRequest request) {
        return governanceService.save(
                workspaceId, authService.getCurrentUser().getId(), request);
    }
}
