package ooo.klae.connex.backend.controllers;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.UserService;
import ooo.klae.connex.backend.services.WorkspaceService;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {
    @Mock private UserService userService;
    @Mock private AuthService authService;
    @Mock private WorkspaceService workspaceService;
    @Mock private SessionSecurityService sessionSecurityService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(
                userService, authService, workspaceService, sessionSecurityService)).build();
    }

    @Test
    void updateCurrentUserLocaleUsesAuthenticatedUser() throws Exception {
        User current = new User();
        current.setId(7);
        User updated = new User();
        updated.setId(7);
        updated.setUsername("member");
        updated.setDisplayName("Member");
        updated.setEmail("member@example.com");
        updated.setTimezone("UTC");
        updated.setLocale("ja");
        when(authService.getCurrentUser()).thenReturn(current);
        when(userService.updateLocale(7, "ja")).thenReturn(updated);

        mockMvc.perform(patch("/api/users/me/locale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"ja\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.locale").value("ja"));

        verify(userService).updateLocale(7, "ja");
    }

    @Test
    void updateCurrentUserLocaleRejectsUnsupportedValueAtBoundary() throws Exception {
        mockMvc.perform(patch("/api/users/me/locale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"fr\"}"))
                .andExpect(status().isBadRequest());

        verify(userService, never()).updateLocale(7, "fr");
    }
}
