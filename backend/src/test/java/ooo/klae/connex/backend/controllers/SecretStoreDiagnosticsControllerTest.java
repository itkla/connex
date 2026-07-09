package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.SecretStoreDiagnosticsDto;
import ooo.klae.connex.backend.secrets.SecretStoreLifecycleService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.OrgMemberService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class SecretStoreDiagnosticsControllerTest {
    @Mock private SecretStoreLifecycleService lifecycleService;
    @Mock private WorkspaceService workspaceService;
    @Mock private OrgMemberService orgMemberService;
    @Mock private AuthService authService;

    private SecretStoreDiagnosticsController controller;

    @BeforeEach
    void setUp() {
        controller = new SecretStoreDiagnosticsController(lifecycleService, workspaceService,
                orgMemberService, authService);
        when(authService.getCurrentUser()).thenReturn(user(7));
    }

    @Test
    void workspaceDiagnosticsRequiresWorkspaceSettings() {
        SecretStoreDiagnosticsDto diagnostics = new SecretStoreDiagnosticsDto();
        when(lifecycleService.diagnosticsForWorkspace(3)).thenReturn(diagnostics);

        SecretStoreDiagnosticsDto response = controller.workspaceDiagnostics(3);

        verify(workspaceService).requirePermission(3, 7, Permission.WORKSPACE_SETTINGS);
        verify(lifecycleService).diagnosticsForWorkspace(3);
        assertSame(diagnostics, response);
    }

    @Test
    void orgDiagnosticsRequiresOrgAdmin() {
        SecretStoreDiagnosticsDto diagnostics = new SecretStoreDiagnosticsDto();
        when(lifecycleService.diagnosticsForOrg(5)).thenReturn(diagnostics);

        SecretStoreDiagnosticsDto response = controller.orgDiagnostics(5);

        verify(orgMemberService).requireOrgAdmin(5, 7);
        verify(lifecycleService).diagnosticsForOrg(5);
        assertSame(diagnostics, response);
    }

    private static User user(int id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
