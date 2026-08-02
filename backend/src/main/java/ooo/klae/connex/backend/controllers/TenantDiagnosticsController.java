package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.MailDiagnosticTestDto;
import ooo.klae.connex.backend.dto.TenantDiagnosticsDto;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.MailDiagnosticsService;
import ooo.klae.connex.backend.services.OrgMemberService;
import ooo.klae.connex.backend.services.TenantDiagnosticsService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;

/**
 * Permission-gated metadata-only tenant diagnostics and self-recipient mail testing.
 */
@RestController
@RequiredArgsConstructor
public class TenantDiagnosticsController {
    private final TenantDiagnosticsService tenantDiagnosticsService;
    private final MailDiagnosticsService mailDiagnosticsService;
    private final WorkspaceService workspaceService;
    private final OrgMemberService orgMemberService;
    private final AuthService authService;

    /** Returns workspace diagnostics after the settings permission gate. */
    @GetMapping("/api/workspaces/{id}/diagnostics")
    public TenantDiagnosticsDto workspaceDiagnostics(@PathVariable int id) {
        int actorId = authService.getCurrentUser().getId();
        workspaceService.requirePermission(id, actorId, Permission.WORKSPACE_SETTINGS);
        return tenantDiagnosticsService.forWorkspace(id, actorId);
    }

    /** Returns organization diagnostics after the organization-admin gate. */
    @GetMapping("/api/orgs/{id}/diagnostics")
    public TenantDiagnosticsDto organizationDiagnostics(@PathVariable int id) {
        int actorId = authService.getCurrentUser().getId();
        orgMemberService.requireOrgAdmin(id, actorId);
        return tenantDiagnosticsService.forOrganization(id, actorId);
    }

    /** Sends a diagnostic email only to the authenticated administrator's stored address. */
    @PostMapping("/api/workspaces/{id}/mail/diagnostics/test-send")
    public MailDiagnosticTestDto testMail(@PathVariable int id) {
        int actorId = authService.getCurrentPrincipal().getId();
        workspaceService.requirePermission(id, actorId, Permission.WORKSPACE_SETTINGS);
        return mailDiagnosticsService.testSend(id, actorId);
    }
}
