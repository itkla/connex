package ooo.klae.connex.backend.controllers;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.UserReferenceDto;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.UserService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.util.PageBounds;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {
    @Mock private UserService userService;
    @Mock private AuthService authService;
    @Mock private WorkspaceService workspaceService;
    @Mock private SessionSecurityService sessionSecurityService;
    @Mock private ErrorReporter errorReporter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserController controller = new UserController(
                userService, authService, workspaceService, sessionSecurityService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(errorReporter, new TenantContext()))
                .build();
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

    @Test
    void getUserReferencesDeduplicatesInStableRequestOrder() throws Exception {
        when(userService.getActiveWorkspaceMemberReferences(List.of(8, 7))).thenReturn(List.of(
            new UserReferenceDto(8, "Eight", "/api/users/8/profile-picture"),
            new UserReferenceDto(7, "Seven", null)
        ));

        mockMvc.perform(get("/api/users/references").param("ids", "8", "7", "8"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(8))
            .andExpect(jsonPath("$[0].displayName").value("Eight"))
            .andExpect(jsonPath("$[0].profilePictureUrl").value("/api/users/8/profile-picture"))
            .andExpect(jsonPath("$[1].id").value(7))
            .andExpect(jsonPath("$[1].displayName").value("Seven"))
            .andExpect(jsonPath("$[1].profilePictureUrl").doesNotExist());

        verify(userService).getActiveWorkspaceMemberReferences(List.of(8, 7));
    }

    @Test
    void getUserReferencesRejectsMissingIds() throws Exception {
        mockMvc.perform(get("/api/users/references"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(userService);
    }

    @Test
    void getUserReferencesRejectsNullIds() throws Exception {
        mockMvc.perform(get("/api/users/references").param("ids", "7", ""))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(userService);
    }

    @Test
    void getUserReferencesRejectsNonpositiveIds() throws Exception {
        mockMvc.perform(get("/api/users/references").param("ids", "0"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/users/references").param("ids", "-1"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(userService);
    }

    @Test
    void getUserReferencesRejectsOversizedRawIdList() throws Exception {
        String[] ids = Collections.nCopies(PageBounds.MAX_SIZE + 1, "7")
            .toArray(String[]::new);

        mockMvc.perform(get("/api/users/references").param("ids", ids))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(userService);
    }
}
