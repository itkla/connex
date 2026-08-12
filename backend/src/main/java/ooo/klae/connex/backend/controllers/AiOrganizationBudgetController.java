package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.AiOrganizationBudgetDto;
import ooo.klae.connex.backend.dto.AiOrganizationBudgetRequest;
import ooo.klae.connex.backend.services.AiOrganizationBudgetService;
import ooo.klae.connex.backend.services.AuthService;

/** Organization-administrator daily AI budget configuration and usage reporting. */
@RestController
@RequestMapping("/api/ai/budget")
@RequiredArgsConstructor
public class AiOrganizationBudgetController {
    private final AiOrganizationBudgetService budgetService;
    private final AuthService authService;

    /** Returns the current organization budget through the active workspace. */
    @GetMapping
    public AiOrganizationBudgetDto get(@RequestParam("workspaceId") int workspaceId) {
        return budgetService.getForWorkspace(
                workspaceId, authService.getCurrentUser().getId());
    }

    /** Replaces the organization daily token limit. */
    @PutMapping
    public AiOrganizationBudgetDto save(
            @RequestParam("workspaceId") int workspaceId,
            @Valid @RequestBody AiOrganizationBudgetRequest request) {
        return budgetService.save(
                workspaceId, authService.getCurrentUser().getId(), request);
    }
}
