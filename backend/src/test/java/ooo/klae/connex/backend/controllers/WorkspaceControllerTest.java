package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.services.AllowedDomainService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.InviteLinkService;
import ooo.klae.connex.backend.services.InviteService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.WorkspaceCookie;

@ExtendWith(MockitoExtension.class)
class WorkspaceControllerTest {
    @Mock private WorkspaceService workspaceService;
    @Mock private InviteService inviteService;
    @Mock private InviteLinkService inviteLinkService;
    @Mock private AllowedDomainService allowedDomainService;
    @Mock private AuthService authService;
    @Mock private WorkspaceCookie workspaceCookie;

    private WorkspaceController controller;

    @BeforeEach
    void setUp() {
        controller = new WorkspaceController(
            workspaceService,
            inviteService,
            inviteLinkService,
            allowedDomainService,
            authService,
            workspaceCookie
        );
        User user = new User();
        user.setId(7);
        when(authService.getCurrentUser()).thenReturn(user);
    }

    @Test
    void leaveSetsNextWorkspaceCookieReturnedByService() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(workspaceService.leaveWorkspaceAndSelectNext(9, 7)).thenReturn(12);

        controller.leave(9, response);

        verify(workspaceService).leaveWorkspaceAndSelectNext(9, 7);
        verify(workspaceCookie).set(response, 12);
        verify(workspaceCookie, never()).clear(response);
    }

    @Test
    void leaveClearsWorkspaceCookieWhenNoMembershipRemains() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(workspaceService.leaveWorkspaceAndSelectNext(9, 7)).thenReturn(null);

        controller.leave(9, response);

        verify(workspaceService).leaveWorkspaceAndSelectNext(9, 7);
        verify(workspaceCookie).clear(response);
        verify(workspaceCookie, never()).set(same(response), anyInt());
    }
}
