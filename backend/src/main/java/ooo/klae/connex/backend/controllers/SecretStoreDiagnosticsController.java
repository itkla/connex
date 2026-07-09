package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.SecretStoreDiagnosticsDto;
import ooo.klae.connex.backend.secrets.SecretStoreLifecycleService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.OrgMemberService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;

/**
 * Metadata-only secret-store key health diagnostics for workspace and org
 * administrators. The response never includes plaintext, ciphertext, wrapped
 * data keys, or key material.
 */
@RestController
@RequiredArgsConstructor
public class SecretStoreDiagnosticsController {
    private final SecretStoreLifecycleService lifecycleService;
    private final WorkspaceService workspaceService;
    private final OrgMemberService orgMemberService;
    private final AuthService authService;

    @GetMapping("/api/workspaces/{workspaceId}/secret-store/diagnostics")
    public SecretStoreDiagnosticsDto workspaceDiagnostics(@PathVariable int workspaceId) {
        workspaceService.requirePermission(workspaceId, authService.getCurrentUser().getId(),
                Permission.WORKSPACE_SETTINGS);
        return lifecycleService.diagnosticsForWorkspace(workspaceId);
    }

    @GetMapping("/api/orgs/{orgId}/secret-store/diagnostics")
    public SecretStoreDiagnosticsDto orgDiagnostics(@PathVariable int orgId) {
        orgMemberService.requireOrgAdmin(orgId, authService.getCurrentUser().getId());
        return lifecycleService.diagnosticsForOrg(orgId);
    }
}
