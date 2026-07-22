package ooo.klae.connex.backend.controllers;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import ooo.klae.connex.backend.dto.RuleExecutionSummaryDto;
import ooo.klae.connex.backend.dto.RuleDto;
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

    @Test
    void listReturnsOnlyTheSafeLatestExecutionProjection() throws Exception {
        RuleDto withHistory = new RuleDto();
        withHistory.setId(42);
        withHistory.setLatestExecution(
            new RuleExecutionSummaryDto("failed", "2026-07-21 10:15:00"));
        RuleDto withoutHistory = new RuleDto();
        withoutHistory.setId(43);
        when(ruleService.list()).thenReturn(List.of(withHistory, withoutHistory));

        mockMvc.perform(get("/api/rules"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].latestExecution.status").value("failed"))
            .andExpect(jsonPath("$[0].latestExecution.executedAt")
                .value("2026-07-21 10:15:00"))
            .andExpect(jsonPath("$[0].latestExecution.workspaceId").doesNotExist())
            .andExpect(jsonPath("$[0].latestExecution.ruleId").doesNotExist())
            .andExpect(jsonPath("$[0].latestExecution.dedupeKey").doesNotExist())
            .andExpect(jsonPath("$[0].latestExecution.detail").doesNotExist())
            .andExpect(jsonPath("$[1].latestExecution").doesNotExist());

        verify(ruleService).list();
    }
}
