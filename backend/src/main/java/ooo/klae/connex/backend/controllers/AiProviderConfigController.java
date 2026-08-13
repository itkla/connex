package ooo.klae.connex.backend.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.AiProviderConfigDto;
import ooo.klae.connex.backend.dto.AiProviderConfigRequest;
import ooo.klae.connex.backend.services.AiProviderConfigService;
import ooo.klae.connex.backend.services.AuthService;

/**
 * Org-administrator AI provider settings addressed through an acting workspace.
 * Organization authorization and recent-authentication checks live in the service.
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiProviderConfigController {
    private final AiProviderConfigService aiProviderConfigService;
    private final AuthService authService;

    @GetMapping("/provider")
    public AiProviderConfigDto get(@RequestParam("workspaceId") int workspaceId) {
        return aiProviderConfigService.getForWorkspace(workspaceId, authService.getCurrentUser().getId());
    }

    @PutMapping("/provider")
    public AiProviderConfigDto save(@RequestParam("workspaceId") int workspaceId,
            @Valid @RequestBody AiProviderConfigRequest request) {
        return aiProviderConfigService.save(workspaceId, authService.getCurrentUser().getId(), request);
    }

    /** Records the current ZDR terms after organization-admin step-up authentication. */
    @PostMapping("/provider/zdr-attestation")
    public AiProviderConfigDto attestZeroDataRetention(
            @RequestParam("workspaceId") int workspaceId) {
        return aiProviderConfigService.attestZeroDataRetention(
                workspaceId, authService.getCurrentUser().getId());
    }

    @DeleteMapping("/provider")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@RequestParam("workspaceId") int workspaceId) {
        aiProviderConfigService.revoke(workspaceId, authService.getCurrentUser().getId());
    }
}
