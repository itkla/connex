package ooo.klae.connex.backend.controllers;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.services.HealthService;
import ooo.klae.connex.backend.services.HealthService.Readiness;
import ooo.klae.connex.backend.services.HealthService.Status;

@ExtendWith(MockitoExtension.class)
class HealthControllerTest {
    @Mock private HealthService healthService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthController(healthService)).build();
    }

    @Test
    void healthReturnsExactLivenessBody() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"status\":\"UP\"}"));
    }

    @Test
    void readyReturnsExactAllUpBody() throws Exception {
        when(healthService.readiness()).thenReturn(new Readiness(Status.UP, Status.UP, Status.UP));

        mockMvc.perform(get("/api/health/ready"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        "{\"status\":\"UP\",\"checks\":{\"db\":\"UP\",\"migrations\":\"UP\","
                                + "\"auditGuard\":\"UP\"}}"));
    }

    @Test
    void readyReturnsOnlyStatusWordsWhenChecksFail() throws Exception {
        when(healthService.readiness()).thenReturn(new Readiness(Status.DOWN, Status.UP, Status.UP));

        mockMvc.perform(get("/api/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(
                        "{\"status\":\"DOWN\",\"checks\":{\"db\":\"DOWN\",\"migrations\":\"UP\","
                                + "\"auditGuard\":\"UP\"}}"));
    }

    @Test
    void aDegradedAuditGuardIsReportedWithoutFailingReadiness() throws Exception {
        when(healthService.readiness()).thenReturn(new Readiness(Status.UP, Status.UP, Status.DOWN));

        mockMvc.perform(get("/api/health/ready"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        "{\"status\":\"UP\",\"checks\":{\"db\":\"UP\",\"migrations\":\"UP\","
                                + "\"auditGuard\":\"DOWN\"}}"));
    }
}
