package ooo.klae.connex.backend.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.TenantTeardownRequest;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.TenantTeardownService;

/** Owner-only destructive workspace and organization lifecycle endpoints. */
@RestController
@RequestMapping("/api/orgs/{orgId}")
@RequiredArgsConstructor
public class TenantTeardownController {
    private final TenantTeardownService tenantTeardownService;
    private final AuthService authService;

    /** Permanently tears down one workspace after owner step-up and confirmation. */
    @DeleteMapping("/workspaces/{workspaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void teardownWorkspace(
            @PathVariable int orgId,
            @PathVariable int workspaceId,
            @Valid @RequestBody TenantTeardownRequest request) {
        tenantTeardownService.teardownWorkspace(
            orgId,
            workspaceId,
            authService.getCurrentUser().getId(),
            request.confirmation());
    }

    /** Permanently tears down an organization and all of its workspaces. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void teardownOrganization(
            @PathVariable int orgId,
            @Valid @RequestBody TenantTeardownRequest request) {
        tenantTeardownService.teardownOrganization(
            orgId,
            authService.getCurrentUser().getId(),
            request.confirmation());
    }
}
