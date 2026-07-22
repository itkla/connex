package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.RuleExecution;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleDto;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.mappers.RuleMapper;

@ExtendWith(MockitoExtension.class)
class RuleServiceListProjectionTest {

    @Mock private RuleMapper ruleMapper;
    @Mock private SegmentService segmentService;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuthService authService;
    @Mock private AuditService auditService;
    @Mock private LegacyRuleWorkflowService legacyRuleWorkflowService;
    @Mock private RuleDefinitionValidator definitionValidator;
    @Mock private RuleDefinitionCodec definitionCodec;
    @InjectMocks private RuleService ruleService;

    @Test
    void listSkipsExecutionQueryWhenWorkspaceHasNoRules() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(ruleMapper.getByWorkspace(7)).thenReturn(List.of());

        assertEquals(List.of(), ruleService.list());

        verify(ruleMapper, never()).getLatestExecutionsByWorkspace(7);
    }

    @Test
    void listUsesOneBatchProjectionAndPreservesRuleOrder() {
        Rule first = rule(23);
        Rule second = rule(19);
        RuleExecution latest = new RuleExecution();
        latest.setRuleId(19);
        latest.setStatus("failed");
        latest.setExecutedAt("2026-07-21 10:15:00");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(ruleMapper.getByWorkspace(7)).thenReturn(List.of(first, second));
        when(ruleMapper.getLatestExecutionsByWorkspace(7)).thenReturn(List.of(latest));
        when(definitionCodec.parse(anyString(), eq(RuleTrigger.class)))
            .thenReturn(new RuleTrigger());
        when(definitionCodec.parse(anyString(), eq(RuleAction[].class)))
            .thenReturn(new RuleAction[0]);

        List<RuleDto> listed = ruleService.list();

        assertEquals(List.of(23, 19), listed.stream().map(RuleDto::getId).toList());
        assertNull(listed.getFirst().getLatestExecution());
        assertEquals("failed", listed.getLast().getLatestExecution().status());
        verify(ruleMapper).getLatestExecutionsByWorkspace(7);
    }

    private static Rule rule(int id) {
        Rule rule = new Rule();
        rule.setId(id);
        rule.setTriggerConfig("{}");
        rule.setActionsJson("[]");
        return rule;
    }
}
