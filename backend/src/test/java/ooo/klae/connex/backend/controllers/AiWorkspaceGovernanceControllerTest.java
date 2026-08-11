package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import ooo.klae.connex.backend.dto.AiWorkspaceGovernanceDto;
import ooo.klae.connex.backend.dto.AiWorkspaceGovernanceRequest;
import ooo.klae.connex.backend.services.AiWorkspaceGovernanceService;
import ooo.klae.connex.backend.services.AuthService;

@ExtendWith(MockitoExtension.class)
class AiWorkspaceGovernanceControllerTest {
    @Mock private AiWorkspaceGovernanceService governanceService;
    @Mock private AuthService authService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        User actor = new User();
        actor.setId(7);
        when(authService.getCurrentUser()).thenReturn(actor);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AiWorkspaceGovernanceController(governanceService, authService))
                .build();
    }

    @Test
    void getAndPutUseTheActingWorkspaceAndValidatedBounds() throws Exception {
        when(governanceService.getForWorkspace(9, 7))
                .thenReturn(new AiWorkspaceGovernanceDto(9, true, 6));
        when(governanceService.save(
                org.mockito.ArgumentMatchers.eq(9),
                org.mockito.ArgumentMatchers.eq(7),
                any(AiWorkspaceGovernanceRequest.class)))
                .thenReturn(new AiWorkspaceGovernanceDto(9, false, 4));

        mockMvc.perform(get("/api/ai/governance?workspaceId=9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.assistantMaxSteps").value(6));
        mockMvc.perform(put("/api/ai/governance?workspaceId=9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"assistantMaxSteps\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mockMvc.perform(put("/api/ai/governance?workspaceId=9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"assistantMaxSteps\":13}"))
                .andExpect(status().isBadRequest());

        verify(governanceService).getForWorkspace(9, 7);
        verify(governanceService).save(
                org.mockito.ArgumentMatchers.eq(9),
                org.mockito.ArgumentMatchers.eq(7),
                any(AiWorkspaceGovernanceRequest.class));
    }
}
