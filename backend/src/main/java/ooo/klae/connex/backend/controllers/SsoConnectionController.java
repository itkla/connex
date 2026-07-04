package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.SsoConnectionDto;
import ooo.klae.connex.backend.dto.SsoConnectionRequest;
import ooo.klae.connex.backend.dto.SsoDiscoveryDto;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.SsoConnectionService;

/**
 * SSO connection endpoints for an organization: the pre-login domain discovery the login
 * screen uses to route to an IdP, plus owner/admin read and upsert of the connection,
 * addressed by the acting workspace. Discovery is unauthenticated and returns only
 * domain-level routing; {@code SSO_MANAGE} is enforced in the service for read/upsert,
 * which resolves the workspace's organization.
 */
@RestController
@RequestMapping("/api/auth/sso")
@RequiredArgsConstructor
public class SsoConnectionController {

    private final SsoConnectionService ssoConnectionService;
    private final AuthService authService;

    @GetMapping("/discover")
    public SsoDiscoveryDto discover(@RequestParam("email") String email) {
        return ssoConnectionService.discoverByEmail(email);
    }

    @GetMapping("/config")
    public SsoConnectionDto get(@RequestParam("workspaceId") int workspaceId) {
        return ssoConnectionService.getForWorkspace(workspaceId, authService.getCurrentUser().getId());
    }

    @PutMapping("/config")
    public SsoConnectionDto save(@RequestParam("workspaceId") int workspaceId,
            @Valid @RequestBody SsoConnectionRequest request) {
        return ssoConnectionService.save(workspaceId, authService.getCurrentUser().getId(), request);
    }
}
