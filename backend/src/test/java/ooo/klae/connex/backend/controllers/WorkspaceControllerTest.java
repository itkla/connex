package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.ResponseStatus;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.UpdateWorkspaceIdentityRequest;
import ooo.klae.connex.backend.dto.WorkspaceIdentityDto;
import ooo.klae.connex.backend.dto.WorkspaceSelectionDto;
import ooo.klae.connex.backend.services.AllowedDomainService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.InviteLinkService;
import ooo.klae.connex.backend.services.InviteService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.WorkspaceCookie;
import tools.jackson.databind.json.JsonMapper;

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
    void leaveSetsNextWorkspaceCookieReturnedByService() throws NoSuchMethodException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(workspaceService.leaveWorkspaceAndSelectNext(9, 7)).thenReturn(12);

        WorkspaceSelectionDto selection = controller.leave(9, response);
        var method = WorkspaceController.class.getDeclaredMethod(
            "leave",
            int.class,
            HttpServletResponse.class
        );

        assertEquals(12, selection.activeWorkspaceId());
        assertEquals(WorkspaceSelectionDto.class, method.getReturnType());
        assertNull(method.getAnnotation(ResponseStatus.class));
        verify(workspaceService).leaveWorkspaceAndSelectNext(9, 7);
        verify(workspaceCookie).set(response, 12);
        verify(workspaceCookie, never()).clear(response);
    }

    @Test
    void leaveClearsWorkspaceCookieWhenNoMembershipRemains() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(workspaceService.leaveWorkspaceAndSelectNext(9, 7)).thenReturn(null);

        WorkspaceSelectionDto selection = controller.leave(9, response);
        JsonMapper jsonMapper = JsonMapper.builder()
            .changeDefaultPropertyInclusion(ignored -> JsonInclude.Value.ALL_NON_NULL)
            .build();

        assertNull(selection.activeWorkspaceId());
        assertEquals("{\"activeWorkspaceId\":null}", jsonMapper.writeValueAsString(selection));
        verify(workspaceService).leaveWorkspaceAndSelectNext(9, 7);
        verify(workspaceCookie).clear(response);
        verify(workspaceCookie, never()).set(same(response), anyInt());
    }

    @Test
    void updateIdentityDelegatesActorAndCompleteMutableIdentity() {
        UpdateWorkspaceIdentityRequest request = new UpdateWorkspaceIdentityRequest();
        request.setName("Renamed");
        request.setTimezone("Pacific/Honolulu");
        request.setExpectedName("Original");
        request.setExpectedTimezone(null);
        request.setExpectedIdentityVersion(4L);
        WorkspaceIdentityDto expected = new WorkspaceIdentityDto(
            9, 3, "Renamed", "immutable", "Pacific/Honolulu", 5L, "2026-08-03 12:00:00");
        when(workspaceService.updateIdentity(
            9, 7, "Renamed", "Pacific/Honolulu", "Original", null, 4L)).thenReturn(expected);

        WorkspaceIdentityDto actual = controller.updateIdentity(9, request);

        assertSame(expected, actual);
        verify(workspaceService).updateIdentity(
            9, 7, "Renamed", "Pacific/Honolulu", "Original", null, 4L);
    }
}
