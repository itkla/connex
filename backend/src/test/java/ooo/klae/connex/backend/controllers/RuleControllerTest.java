package ooo.klae.connex.backend.controllers;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.dto.RuleExecutionDto;
import ooo.klae.connex.backend.services.RuleService;

@ExtendWith(MockitoExtension.class)
class RuleControllerTest {

    @Mock private RuleService ruleService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new RuleController(ruleService)).build();
    }

    @Test
    void executionsReturnsOnlyTheSafePublicProjection() throws Exception {
        when(ruleService.executions(42)).thenReturn(List.of(new RuleExecutionDto(
            17, "deal", 23, "failed", "2026-07-21 10:15:00")));

        mockMvc.perform(get("/api/rules/42/executions"))
            .andExpect(status().isOk())
            .andExpect(content().string(
                "[{\"id\":17,\"triggerEntityType\":\"deal\",\"triggerEntityId\":23,"
                    + "\"status\":\"failed\",\"executedAt\":\"2026-07-21 10:15:00\"}]"));

        verify(ruleService).executions(42);
    }
}
