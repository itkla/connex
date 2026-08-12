package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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
import ooo.klae.connex.backend.dto.AiOrganizationBudgetDto;
import ooo.klae.connex.backend.dto.AiOrganizationBudgetRequest;
import ooo.klae.connex.backend.services.AiOrganizationBudgetService;
import ooo.klae.connex.backend.services.AuthService;

@ExtendWith(MockitoExtension.class)
class AiOrganizationBudgetControllerTest {
    @Mock private AiOrganizationBudgetService budgetService;
    @Mock private AuthService authService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        User actor = new User();
        actor.setId(7);
        when(authService.getCurrentUser()).thenReturn(actor);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AiOrganizationBudgetController(budgetService, authService))
                .build();
    }

    @Test
    void endpointReturnsAndUpdatesTheExplicitExhaustionState() throws Exception {
        AiOrganizationBudgetDto exhausted = new AiOrganizationBudgetDto(
                3, LocalDate.of(2026, 8, 10), 100, 100, 0, 0, true, List.of());
        when(budgetService.getForWorkspace(9, 7)).thenReturn(exhausted);
        when(budgetService.save(
                org.mockito.ArgumentMatchers.eq(9),
                org.mockito.ArgumentMatchers.eq(7),
                any(AiOrganizationBudgetRequest.class))).thenReturn(exhausted);

        mockMvc.perform(get("/api/ai/budget?workspaceId=9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exhausted").value(true))
                .andExpect(jsonPath("$.remainingUsage").value(0));
        mockMvc.perform(put("/api/ai/budget?workspaceId=9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyUsageLimit\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyUsageLimit").value(100));
        mockMvc.perform(put("/api/ai/budget?workspaceId=9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyUsageLimit\":-1}"))
                .andExpect(status().isBadRequest());

        verify(budgetService).getForWorkspace(9, 7);
        verify(budgetService).save(
                org.mockito.ArgumentMatchers.eq(9),
                org.mockito.ArgumentMatchers.eq(7),
                any(AiOrganizationBudgetRequest.class));
    }
}
