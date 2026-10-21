package ooo.klae.connex.backend.controllers;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiStandingApprovalPolicyDto;
import ooo.klae.connex.backend.services.AiStandingApprovalPolicyService;
import ooo.klae.connex.backend.services.AuthService;

class AiStandingApprovalPolicyControllerTest {
    @Test
    void versionsAreLosslessAndInvalidBoundaryValuesNeverReachTheService() throws Exception {
        var policy = mock(AiStandingApprovalPolicyService.class);
        var auth = mock(AuthService.class);
        var actor = new User();
        actor.setId(7);
        when(auth.getCurrentUser()).thenReturn(actor);
        var dto = new AiStandingApprovalPolicyDto(true, false, false, List.of(), 100, 10,
                "9223372036854775807", "9223372036854775807");
        when(policy.read(9, 7)).thenReturn(dto);
        var mvc = MockMvcBuilders.standaloneSetup(new AiStandingApprovalPolicyController(policy, auth)).build();
        mvc.perform(get("/api/ai/standing-approval-policy?workspaceId=9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stateVersion").value("9223372036854775807"))
                .andExpect(jsonPath("$.killEpoch").value("9223372036854775807"));
        for (String body : List.of("{}", "{\"enabled\":false,\"allowedTools\":[],\"maximumDailyCap\":0,\"maximumPerRunCap\":1,\"expectedStateVersion\":\"1\"}",
                "{\"enabled\":false,\"allowedTools\":[],\"maximumDailyCap\":1,\"maximumPerRunCap\":1,\"expectedStateVersion\":\"01\"}")) {
            mvc.perform(put("/api/ai/standing-approval-policy?workspaceId=9").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(policy, never()).replace(anyInt(), anyInt(), any());
    }
}
