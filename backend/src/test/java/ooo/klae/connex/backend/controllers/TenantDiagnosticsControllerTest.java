package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.MailDiagnosticsService;
import ooo.klae.connex.backend.services.OrgMemberService;
import ooo.klae.connex.backend.services.TenantDiagnosticsService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;

class TenantDiagnosticsControllerTest {
    private static final int ACTOR_ID = 31;
    private static final int WORKSPACE_ID = 41;
    private static final int ORG_ID = 51;

    private TenantDiagnosticsService tenantDiagnosticsService;
    private MailDiagnosticsService mailDiagnosticsService;
    private WorkspaceService workspaceService;
    private OrgMemberService orgMemberService;
    private AuthService authService;
    private TenantDiagnosticsController controller;

    @BeforeEach
    void setUp() {
        tenantDiagnosticsService = mock(TenantDiagnosticsService.class);
        mailDiagnosticsService = mock(MailDiagnosticsService.class);
        workspaceService = mock(WorkspaceService.class);
        orgMemberService = mock(OrgMemberService.class);
        authService = mock(AuthService.class);
        controller = new TenantDiagnosticsController(
                tenantDiagnosticsService,
                mailDiagnosticsService,
                workspaceService,
                orgMemberService,
                authService);
        when(authService.getCurrentUser()).thenReturn(actor());
    }

    @Test
    void workspaceGetRequiresWorkspaceSettingsBeforeDiagnostics() {
        doThrow(new ForbiddenException("denied"))
                .when(workspaceService)
                .requirePermission(WORKSPACE_ID, ACTOR_ID, Permission.WORKSPACE_SETTINGS);

        assertThrows(
                ForbiddenException.class,
                () -> controller.workspaceDiagnostics(WORKSPACE_ID));

        verify(tenantDiagnosticsService, never()).forWorkspace(WORKSPACE_ID, ACTOR_ID);
    }

    @Test
    void organizationGetRequiresOrgAdminBeforeDiagnostics() {
        doThrow(new ForbiddenException("denied"))
                .when(orgMemberService).requireOrgAdmin(ORG_ID, ACTOR_ID);

        assertThrows(
                ForbiddenException.class,
                () -> controller.organizationDiagnostics(ORG_ID));

        verify(tenantDiagnosticsService, never()).forOrganization(ORG_ID, ACTOR_ID);
    }

    @Test
    void mailTestRequiresWorkspaceSettingsBeforeServiceStepUp() {
        doThrow(new ForbiddenException("denied"))
                .when(workspaceService)
                .requirePermission(WORKSPACE_ID, ACTOR_ID, Permission.WORKSPACE_SETTINGS);

        assertThrows(ForbiddenException.class, () -> controller.testMail(WORKSPACE_ID));

        verify(mailDiagnosticsService, never()).testSend(WORKSPACE_ID, ACTOR_ID);
    }

    @Test
    void successfulGatesDelegateWithTheAuthorizedActor() {
        controller.workspaceDiagnostics(WORKSPACE_ID);
        controller.organizationDiagnostics(ORG_ID);
        controller.testMail(WORKSPACE_ID);

        verify(authService, times(3)).getCurrentUser();
        verify(workspaceService, times(2)).requirePermission(
                WORKSPACE_ID, ACTOR_ID, Permission.WORKSPACE_SETTINGS);
        verify(orgMemberService).requireOrgAdmin(ORG_ID, ACTOR_ID);
        verify(tenantDiagnosticsService).forWorkspace(WORKSPACE_ID, ACTOR_ID);
        verify(tenantDiagnosticsService).forOrganization(ORG_ID, ACTOR_ID);
        verify(mailDiagnosticsService).testSend(WORKSPACE_ID, ACTOR_ID);
    }

    private static User actor() {
        User user = new User();
        user.setId(ACTOR_ID);
        return user;
    }
}
