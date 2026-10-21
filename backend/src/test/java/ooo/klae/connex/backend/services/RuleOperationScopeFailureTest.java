package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.support.RecordVisibilityOperationFixture;
import tools.jackson.databind.ObjectMapper;

/** Refuses missing rule scopes before durable claims or action failure translation. */
class RuleOperationScopeFailureTest {
    @ParameterizedTest
    @ValueSource(strings = {"ordinary", "snapshot", "missing"})
    void unconditionalDealRuleRefusesBeforeClaimAndDoesNotRunLaterActions(String kind) {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            RuleMapper rules = mock(RuleMapper.class);
            var claims = mock(WorkflowRuntimeClaimService.class);
            var actions = mock(RuleActionExecutor.class);
            Rule rule = rule("{\"type\":\"entity_change\",\"events\":[\"deal.updated\"]}");
            when(rules.getEnabledByTrigger(7, "entity_change")).thenReturn(List.of(rule));
            RuntimeException failure = failure(kind);
            doThrow(failure).when(fixture.control).findOrganizationWorkspacesForShare(7);
            var engine = engine(fixture, rules, claims, actions);
            var dispatch = new WorkflowTriggerDispatch.EntityChange(
                    7, "deal", 41, "deal.updated", "event-7", Instant.EPOCH);

            assertSame(failure, assertThrows(RuntimeException.class, () -> engine.onEntityChange(dispatch)));

            verify(rules).getEnabledByTrigger(7, "entity_change");
            verify(fixture.control).findOrganizationWorkspacesForShare(7);
            verifyNoInteractions(claims, actions);
            verify(rules, never()).updateExecution(anyInt(), anyInt(), anyString(), any());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ordinary", "snapshot", "missing"})
    void scheduledDealRuleRefusesBeforeConditionHandlingAndClaim(String kind) {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            RuleMapper rules = mock(RuleMapper.class);
            var claims = mock(WorkflowRuntimeClaimService.class);
            var actions = mock(RuleActionExecutor.class);
            Rule rule = rule("{\"type\":\"schedule\",\"cadence\":\"daily\"}");
            rule.setConditionJson("{\"match\":\"all\",\"conditions\":[]}");
            when(rules.getEnabledByTrigger(7, "schedule")).thenReturn(List.of(rule));
            RuntimeException failure = failure(kind);
            doThrow(failure).when(fixture.control).findOrganizationWorkspacesForShare(7);
            var engine = engine(fixture, rules, claims, actions);

            assertSame(failure, assertThrows(RuntimeException.class,
                    () -> engine.runSchedule(new WorkflowTriggerDispatch.ScheduleTick(7, "daily", "day-7"))));

            verify(fixture.control).findOrganizationWorkspacesForShare(7);
            verifyNoInteractions(claims, actions);
            verify(rules, never()).updateExecution(anyInt(), anyInt(), anyString(), any());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unmatchedDealRuleDoesNotResolveScope(boolean schedule) {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            RuleMapper rules = mock(RuleMapper.class);
            var claims = mock(WorkflowRuntimeClaimService.class);
            var actions = mock(RuleActionExecutor.class);
            Rule rule = rule(schedule ? "{\"type\":\"schedule\",\"cadence\":\"weekly\"}"
                    : "{\"type\":\"entity_change\",\"events\":[\"deal.created\"]}");
            when(rules.getEnabledByTrigger(7, schedule ? "schedule" : "entity_change")).thenReturn(List.of(rule));
            var engine = engine(fixture, rules, claims, actions);

            if (schedule) {
                engine.runSchedule(new WorkflowTriggerDispatch.ScheduleTick(7, "daily", "day-7"));
            } else {
                engine.onEntityChange(new WorkflowTriggerDispatch.EntityChange(
                        7, "deal", 41, "deal.updated", "event-7", Instant.EPOCH));
            }

            verifyNoInteractions(fixture.control, claims, actions);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "false,ordinary", "false,snapshot", "false,missing", "true,ordinary", "true,snapshot", "true,missing"
    })
    void directWorkflowEntriesRefuseScopeBeforeTheirClaim(boolean schedule, String kind) {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            RuleMapper rules = mock(RuleMapper.class);
            var workflows = mock(WorkflowMapper.class);
            var claims = mock(WorkflowRuntimeClaimService.class);
            var actions = mock(RuleActionExecutor.class);
            Rule rule = rule(schedule ? "{\"type\":\"schedule\",\"cadence\":\"daily\"}"
                    : "{\"type\":\"entity_change\",\"events\":[\"deal.updated\"]}");
            var workflow = new ooo.klae.connex.backend.beans.Workflow();
            workflow.setLegacyRuleId(23);
            when(workflows.getById(7, 33)).thenReturn(workflow);
            when(rules.getById(7, 23)).thenReturn(rule);
            var engine = engine(fixture, rules, claims, actions);
            org.springframework.test.util.ReflectionTestUtils.setField(engine, "workflowMapper", workflows);
            RuntimeException failure = failure(kind);
            doThrow(failure).when(fixture.control).findOrganizationWorkspacesForShare(7);

            assertSame(failure, assertThrows(RuntimeException.class, () -> {
                if (schedule) {
                    engine.runScheduleRecordForWorkflow(33,
                            new WorkflowTriggerDispatch.ScheduleTick(7, "daily", "day-7"), 41);
                } else {
                    engine.onEntityChangeForWorkflow(33, new WorkflowTriggerDispatch.EntityChange(
                            7, "deal", 41, "deal.updated", "event-7", Instant.EPOCH));
                }
            }));

            verify(fixture.control).findOrganizationWorkspacesForShare(7);
            verifyNoInteractions(claims, actions);
        }
    }

    private static Rule rule(String trigger) {
        Rule rule = new Rule();
        rule.setId(23);
        rule.setWorkspaceId(7);
        rule.setRecordType("deal");
        rule.setTriggerConfig(trigger);
        rule.setActionsJson("[{\"type\":\"change_stage\",\"targetStageId\":99},"
                + "{\"type\":\"assign_owner\",\"targetUserId\":17}]");
        return rule;
    }

    private static RuntimeException failure(String kind) {
        return switch (kind) {
            case "snapshot" -> new TenantScopeSnapshotException("scope mismatch");
            case "missing" -> new ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException("missing scope");
            default -> new DataAccessResourceFailureException("control unavailable");
        };
    }

    private static RuleEngineService engine(RecordVisibilityOperationFixture fixture, RuleMapper rules,
            WorkflowRuntimeClaimService claims, RuleActionExecutor actions) {
        return new RuleEngineService(rules, fixture.scopes, mock(DealMapper.class), mock(PersonMapper.class),
                mock(SegmentService.class), actions, mock(RuleActionIsolation.class),
                mock(AutomationExecutor.class), mock(UserMapper.class), mock(WorkspaceService.class),
                mock(SystemActor.class), new ObjectMapper(), claims, mock(WorkflowMapper.class),
                mock(WorkflowTriggeredSendGate.class), mock(AuditService.class));
    }
}
