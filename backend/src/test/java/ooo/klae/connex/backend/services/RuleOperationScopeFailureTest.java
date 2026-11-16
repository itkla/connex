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
            rule.setRunAsUserId(17);
            var engine = engine(fixture, rules, claims, actions);
            org.springframework.test.util.ReflectionTestUtils.setField(engine, "segmentService",
                    segmentService(fixture, mock(ooo.klae.connex.backend.mappers.SegmentMapper.class)));

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyScheduledSendCandidatesSkipScopeAndContinueWithFollowingRules(boolean nonpositiveUpperId) {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            var rules = mock(RuleMapper.class);
            var claims = mock(WorkflowRuntimeClaimService.class);
            var actions = mock(RuleActionExecutor.class);
            var segments = mock(ooo.klae.connex.backend.mappers.SegmentMapper.class);
            var first = rule("{\"type\":\"schedule\",\"cadence\":\"daily\"}");
            first.setRecordType("person");
            first.setRunAsUserId(17);
            first.setActionsJson("[{\"type\":\"send_message\"}]");
            first.setConditionJson("{\"match\":\"all\",\"conditions\":[]}");
            var following = rule(first.getTriggerConfig());
            following.setId(24);
            following.setRecordType("person");
            following.setRunAsUserId(17);
            following.setActionsJson(first.getActionsJson());
            following.setConditionJson(first.getConditionJson());
            when(rules.getEnabledByTrigger(7, "schedule")).thenReturn(List.of(first, following));
            when(segments.maximumEntityId(7, "person")).thenReturn(nonpositiveUpperId ? 0 : null);
            doThrow(new DataAccessResourceFailureException("control unavailable"))
                    .when(fixture.control).findOrganizationWorkspacesForShare(7);
            var engine = engine(fixture, rules, claims, actions);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    engine, "segmentService", segmentService(fixture, segments));

            engine.runSchedule(new WorkflowTriggerDispatch.ScheduleTick(7, "daily", "day-7"));

            verify(segments, org.mockito.Mockito.times(2)).maximumEntityId(7, "person");
            verifyNoInteractions(fixture.control, claims, actions);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "action,snapshot", "action,missing", "automation,snapshot", "automation,missing"
    })
    void snapshotRefusalSettlesACommittedClaimBeforeRethrowAndRetry(String source, String kind) {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            var rules = mock(RuleMapper.class);
            var persisted = new java.util.HashMap<String, ooo.klae.connex.backend.beans.RuleExecution>();
            org.mockito.Mockito.doAnswer(call -> {
                var execution = call.getArgument(0, ooo.klae.connex.backend.beans.RuleExecution.class);
                execution.setId(31);
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                persisted.put(execution.getDedupeKey(), execution);
                            }
                        });
                return 1;
            }).when(rules).insertExecution(any());
            when(rules.getExecutionByDedupe(eq(7), eq(23), anyString()))
                    .thenAnswer(call -> persisted.get(call.getArgument(2, String.class)));
            org.mockito.Mockito.doAnswer(call -> {
                var execution = persisted.values().iterator().next();
                execution.setStatus(call.getArgument(2, String.class));
                execution.setDetail(call.getArgument(3, String.class));
                return null;
            }).when(rules).updateExecution(eq(7), eq(31), anyString(), any());
            var claimTarget = new WorkflowRuntimeClaimService(mock(WorkflowMapper.class),
                    mock(ooo.klae.connex.backend.mappers.WorkflowVersionMapper.class),
                    mock(ooo.klae.connex.backend.mappers.WorkflowRunMapper.class),
                    mock(ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper.class),
                    rules, mock(DealMapper.class), mock(WorkflowDraftCanonicalizer.class),
                    mock(WorkflowDefinitionValidator.class), new WorkflowDedupeKey(java.time.Clock.systemUTC()),
                    mock(SystemActor.class), mock(ooo.klae.connex.backend.mappers.WorkflowOperationsMapper.class),
                    mock(WorkspaceService.class));
            var proxy = new org.springframework.aop.framework.ProxyFactory(claimTarget);
            proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                    fixture.transactions, new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
            var claims = (WorkflowRuntimeClaimService) proxy.getProxy();
            var actions = mock(RuleActionExecutor.class);
            var isolation = mock(RuleActionIsolation.class);
            org.mockito.Mockito.doAnswer(call -> {
                call.getArgument(0, Runnable.class).run();
                return null;
            }).when(isolation).run(any(Runnable.class));
            var automation = mock(AutomationExecutor.class);
            RuntimeException refusal = failure(kind);
            when(automation.runAs(eq(7), any(ooo.klae.connex.backend.beans.User.class), eq("member"),
                    any(AutomationScope.Origin.class), any(java.util.function.Supplier.class))).thenAnswer(call -> {
                org.junit.jupiter.api.Assertions.assertNull(fixture.transactions.currentTransaction());
                org.junit.jupiter.api.Assertions.assertEquals(1, persisted.size());
                org.junit.jupiter.api.Assertions.assertEquals("running", persisted.values().iterator().next().getStatus());
                if (source.equals("automation")) {
                    throw refusal;
                }
                return call.getArgument(4, java.util.function.Supplier.class).get();
            });
            when(actions.execute(any(), any())).thenThrow(refusal);
            var user = new ooo.klae.connex.backend.beans.User();
            user.setId(17);
            var users = mock(UserMapper.class);
            var workspaces = mock(WorkspaceService.class);
            when(users.getUserById(17)).thenReturn(user);
            when(workspaces.getRole(7, 17)).thenReturn("member");
            var rule = rule("{\"type\":\"entity_change\",\"events\":[\"deal.updated\"]}");
            rule.setEnabled(true);
            rule.setExecutionMode("user");
            rule.setRunAsUserId(17);
            when(rules.getEnabledByTrigger(7, "entity_change")).thenReturn(List.of(rule));
            var engine = new RuleEngineService(rules, fixture.scopes, mock(DealMapper.class), mock(PersonMapper.class),
                    mock(SegmentService.class), actions, isolation, automation, users, workspaces,
                    mock(SystemActor.class), new ObjectMapper(), claims, mock(WorkflowMapper.class),
                    mock(WorkflowTriggeredSendGate.class), mock(AuditService.class));
            var dispatch = new WorkflowTriggerDispatch.EntityChange(
                    7, "deal", 41, "deal.updated", "event-7", Instant.EPOCH);

            assertSame(refusal, assertThrows(TenantScopeSnapshotException.class, () -> engine.onEntityChange(dispatch)));

            var execution = persisted.values().iterator().next();
            org.junit.jupiter.api.Assertions.assertEquals("failed", execution.getStatus());
            org.junit.jupiter.api.Assertions.assertTrue(execution.getDetail().contains("execution_failed"));
            org.junit.jupiter.api.Assertions.assertEquals(1, fixture.transactions.beginCount());
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> engine.onEntityChange(dispatch));
            org.junit.jupiter.api.Assertions.assertEquals("failed", execution.getStatus());
            org.junit.jupiter.api.Assertions.assertEquals(1, persisted.size());
            verify(rules).insertExecution(any());
            verify(rules).updateExecution(eq(7), eq(31), eq("failed"), anyString());
            verify(automation).runAs(eq(7), any(ooo.klae.connex.backend.beans.User.class), eq("member"),
                    any(AutomationScope.Origin.class), any(java.util.function.Supplier.class));
        }
    }

    private static SegmentService segmentService(RecordVisibilityOperationFixture fixture,
            ooo.klae.connex.backend.mappers.SegmentMapper segments) {
        return new SegmentService(mock(WorkspaceService.class), mock(AuthService.class), mock(ScoringService.class),
                mock(DealRiskService.class), segments, mock(PersonEdgeReadService.class), mock(PersonMapper.class),
                mock(ooo.klae.connex.backend.mappers.TagMapper.class), new SegmentCatalog(), fixture.scopes);
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
