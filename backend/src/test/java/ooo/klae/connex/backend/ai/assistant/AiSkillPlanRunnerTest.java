package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.notifications.AiChatRealtimeDispatcher;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AiSkillPlanRunnerTest {
    private static final int WORKSPACE_ID = 7;
    private static final int USER_ID = 11;
    private static final AiChatQueuedTurn TURN = new AiChatQueuedTurn(
            WORKSPACE_ID, USER_ID, 3, 19, 41, 1, 5L, true,
            List.of(), List.of(), AiPrivacyMode.MASKED, false, AiChatQueryScope.none());

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final AiSkillCatalog skillCatalog = new AiSkillCatalog();

    private AiChatTurnPersistenceService persistenceService;
    private AiAssistantScopeReadService scopeReadService;
    private WorkspaceService workspaceService;
    private AiSkillPlanRunner runner;
    private final ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess recordScopes =
            ooo.klae.connex.backend.support.RecordVisibilityScopes.unfiltered();

    @BeforeEach
    void setUp() {
        persistenceService = mock(AiChatTurnPersistenceService.class);
        scopeReadService = mock(AiAssistantScopeReadService.class);
        workspaceService = mock(WorkspaceService.class);
        when(workspaceService.permissionsFor(WORKSPACE_ID, USER_ID))
                .thenReturn(Set.of(Permission.AI_USE));
        when(persistenceService.proposeTool(any(), anyInt(), anyInt(), anyString(), anyString()))
                .thenReturn(88);
        runner = new AiSkillPlanRunner(
                persistenceService,
                mock(AiAssistantToolExecutor.class),
                scopeReadService,
                mock(AiAssistantWorkBriefReadService.class),
                new AiAssistantPromptAssembler(objectMapper, new AiAssistantToolCatalog()),
                mock(AiChatRealtimeDispatcher.class),
                workspaceService,
                objectMapper, recordScopes);
    }

    /**
     * A saved view that cannot be executed raises a refusal the plan never anticipated. The durable
     * tool call it already proposed must still reach a terminal state: a proposed row left behind
     * stalls the viewer's progress trail and silently breaks the bounded-partial contract.
     */
    @Test
    void aFailureThePlanNeverAnticipatedStillClosesTheDurableToolCall() {
        when(scopeReadService.scopeActivities(
                any(), any(), any(), anyList(), any(), anyInt(), anyInt(), any()))
                .thenThrow(AiAssistantLoopException.malformed("saved_view_scope_unsupported"));

        AiSkillPlanRunner.Execution execution = runner.run(
                TURN, routing("activity_digest_v1"), AiChatQueryScope.none(),
                new AiChatResourceRegistry(), 16_384, () -> { });

        assertFalse(execution.executed());
        assertTrue(execution.degraded());
        assertEquals(1, execution.lastStepNumber());
        ArgumentCaptor<String> result = ArgumentCaptor.forClass(String.class);
        verify(persistenceService).failTool(eq(TURN), eq(88), result.capture());
        assertTrue(result.getValue().contains("saved_view_scope_unsupported"));
    }

    @Test
    void anUncategorizedRuntimeFaultAlsoFailsTheToolRatherThanEscaping() {
        when(scopeReadService.scopeActivities(
                any(), any(), any(), anyList(), any(), anyInt(), anyInt(), any()))
                .thenThrow(new IllegalStateException("segment membership could not be combined"));

        AiSkillPlanRunner.Execution execution = runner.run(
                TURN, routing("activity_digest_v1"), AiChatQueryScope.none(),
                new AiChatResourceRegistry(), 16_384, () -> { });

        assertTrue(execution.degraded());
        verify(persistenceService).failTool(eq(TURN), eq(88), anyString());
    }

    /**
     * Routing checked the permission on the request thread, before any lock. The plan runs later on
     * the generation thread, so the check is re-asserted rather than trusted.
     */
    @Test
    void aPermissionWithdrawnAfterRoutingStopsThePlanBeforeItsFirstRead() {
        when(workspaceService.permissionsFor(WORKSPACE_ID, USER_ID)).thenReturn(Set.of());

        assertThrows(ForbiddenException.class, () -> runner.run(
                TURN, routing("activity_digest_v1"), AiChatQueryScope.none(),
                new AiChatResourceRegistry(), 16_384, () -> { }));

        verify(persistenceService, never()).proposeTool(
                any(), anyInt(), anyInt(), anyString(), anyString());
    }

    /**
     * A server-owned plan step is always the only call its step makes.
     *
     * <p>It therefore proposes under the sole-call ordinal, which renders the exact unsuffixed
     * durable key this runner has always written — the key the model loop's own step numbering
     * interleaves with in one namespace, and the one the progress projection scans by prefix.
     */
    @Test
    void aServerOwnedPlanStepProposesUnderTheSoleCallOrdinal() {
        when(scopeReadService.scopeActivities(
                any(), any(), any(), anyList(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new AiAssistantToolResult(java.util.Map.of("records", List.of()),
                        List.of()));

        runner.run(
                TURN,
                new AiSkillRouter.Routing(
                        skillCatalog.find("activity_digest_v1").orElseThrow(),
                        AiSkillRouter.MATCHED,
                        new AiSkillRouter.Subject("person", 12),
                        false),
                AiChatQueryScope.none(),
                new AiChatResourceRegistry(), 16_384, () -> { });

        verify(persistenceService).proposeTool(
                any(), anyInt(), eq(AiAssistantToolCallRef.SOLE_CALL), anyString(), anyString());
    }

    /**
     * The page anchor reaches the read as context, never as a narrowing argument, so it cannot
     * override a record kind the requester already declared and confirmed.
     */
    @Test
    void theAnchoringRecordReachesTheScopeReadAsContextRatherThanAsAnArgument() {
        when(scopeReadService.scopeActivities(
                any(), any(), any(), anyList(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new AiAssistantToolResult(java.util.Map.of("records", List.of()),
                        List.of()));

        runner.run(
                TURN,
                new AiSkillRouter.Routing(
                        skillCatalog.find("activity_digest_v1").orElseThrow(),
                        AiSkillRouter.MATCHED,
                        new AiSkillRouter.Subject("person", 12),
                        false),
                AiChatQueryScope.none(),
                new AiChatResourceRegistry(), 16_384, () -> { });

        verify(scopeReadService).scopeActivities(
                any(), eq(null), eq("person"), eq(List.of()), any(), anyInt(), anyInt(), any());
    }

    /**
     * A subject skill's activity read is the evidence its answer is built from. Passing the turn's
     * declared scope into it is what stops a bounded-period brief from grounding itself in activity
     * the requester's own period statement excluded.
     */
    @Test
    void aSubjectPlansActivityReadCarriesTheTurnsDeclaredScopeIntoTheExecutor() {
        AiAssistantToolExecutor toolExecutor = mock(AiAssistantToolExecutor.class);
        when(toolExecutor.execute(anyString(), any(), any(), anyBoolean(), any()))
                .thenReturn(new AiAssistantToolResult(java.util.Map.of("handle", "r1"), List.of()));
        AiSkillPlanRunner subjectRunner = new AiSkillPlanRunner(
                persistenceService, toolExecutor, scopeReadService,
                mock(AiAssistantWorkBriefReadService.class),
                new AiAssistantPromptAssembler(objectMapper, new AiAssistantToolCatalog()),
                mock(AiChatRealtimeDispatcher.class), workspaceService, objectMapper, recordScopes);
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        resources.register("person", 12);
        AiChatQueryScope declared = new AiChatQueryScope(
                true, java.time.LocalDate.parse("2026-08-01"),
                java.time.LocalDate.parse("2026-08-22"), 22,
                ooo.klae.connex.backend.dto.MemberScope.allTeam(), List.of(), List.of(),
                List.of(), List.of(), List.of(), null);

        subjectRunner.run(
                TURN,
                new AiSkillRouter.Routing(
                        skillCatalog.find("relationship_brief_v1").orElseThrow(),
                        AiSkillRouter.MATCHED,
                        new AiSkillRouter.Subject("person", 12),
                        false),
                declared,
                resources, 16_384, () -> { });

        verify(toolExecutor).execute(
                eq("list_activities"), any(), any(), anyBoolean(), eq(declared));
    }

    @Test
    void fourRetrievalStepsKeepOneScopeAcrossIndependentTransactions() {
        try (var operation = new ooo.klae.connex.backend.support.RecordVisibilityOperationFixture(7)) {
            AiAssistantToolExecutor executor = mock(AiAssistantToolExecutor.class);
            java.util.function.Supplier<AiAssistantToolResult> read = () ->
                new org.springframework.transaction.support.TransactionTemplate(operation.transactions).execute(status -> {
                    assertEquals(List.of(7, 17), operation.scopes.getForWorkspace(7).workspaceIds());
                    return new AiAssistantToolResult(java.util.Map.of("handle", "r1"), List.of());
                });
            when(executor.execute(anyString(), any(), any(), anyBoolean(), any())).thenAnswer(call -> read.get());
            when(scopeReadService.relationshipMetrics(anyString(), anyInt(), anyString())).thenAnswer(call -> read.get());
            AiSkillPlanRunner scoped = new AiSkillPlanRunner(persistenceService, executor, scopeReadService,
                mock(AiAssistantWorkBriefReadService.class),
                new AiAssistantPromptAssembler(objectMapper, new AiAssistantToolCatalog()),
                mock(AiChatRealtimeDispatcher.class), workspaceService, objectMapper, operation.scopes);
            AiChatResourceRegistry resources = new AiChatResourceRegistry();
            resources.register("company", 41);
            var result = scoped.run(TURN, new AiSkillRouter.Routing(
                skillCatalog.find("relationship_brief_v1").orElseThrow(), AiSkillRouter.MATCHED,
                new AiSkillRouter.Subject("company", 41), false), AiChatQueryScope.none(), resources, 16_384, () -> { });
            assertEquals(4, result.lastStepNumber());
            assertEquals(4, operation.transactions.beginCount());
            verify(operation.control).findOrganizationWorkspacesForShare(7);
        }
    }

    @Test
    void anUnmatchedSkillDoesNotResolveAScope() {
        assertEquals(AiSkillPlanRunner.Execution.none(), runner.run(TURN,
                new AiSkillRouter.Routing(null, "unmatched", null, false), AiChatQueryScope.none(),
                new AiChatResourceRegistry(), 16_384, () -> { }));
        org.mockito.Mockito.verifyNoInteractions(recordScopes, persistenceService, scopeReadService);
    }

    @Test
    void aSkillWithoutItsRequiredSubjectDoesNotResolveAScope() {
        assertEquals(AiSkillPlanRunner.Execution.none(), runner.run(TURN,
                routing("relationship_brief_v1"), AiChatQueryScope.none(),
                new AiChatResourceRegistry(), 16_384, () -> { }));
        org.mockito.Mockito.verifyNoInteractions(recordScopes, persistenceService, scopeReadService);
    }

    @Test
    void anOrdinaryPreloadFailureStillClosesTheRequiredDurableStep() {
        try (var operation = new ooo.klae.connex.backend.support.RecordVisibilityOperationFixture(7)) {
            when(operation.control.findOrganizationWorkspacesForShare(7))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("control unavailable"));
            when(scopeReadService.scopeActivities(any(), any(), any(), anyList(), any(), anyInt(), anyInt(), any()))
                .thenAnswer(call -> {
                    operation.scopes.getForWorkspace(7);
                    throw new AssertionError("failed scope must prevent the read");
                });
            AiSkillPlanRunner scoped = new AiSkillPlanRunner(persistenceService, mock(AiAssistantToolExecutor.class),
                scopeReadService, mock(AiAssistantWorkBriefReadService.class),
                new AiAssistantPromptAssembler(objectMapper, new AiAssistantToolCatalog()),
                mock(AiChatRealtimeDispatcher.class), workspaceService, objectMapper, operation.scopes);
            var result = scoped.run(TURN, routing("activity_digest_v1"), AiChatQueryScope.none(),
                new AiChatResourceRegistry(), 16_384, () -> verify(operation.control).findOrganizationWorkspacesForShare(7));
            assertTrue(result.degraded());
            assertEquals(1, result.lastStepNumber());
            verify(persistenceService).failTool(eq(TURN), eq(88), anyString());
            verify(operation.control).findOrganizationWorkspacesForShare(7);
        }
    }

    @Test
    void aSnapshotRefusalStopsTheSkillBeforeItsFirstGuardOrProposal() {
        try (var operation = new ooo.klae.connex.backend.support.RecordVisibilityOperationFixture(7)) {
            var refusal = new ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException("workspace 7");
            when(operation.control.findOrganizationWorkspacesForShare(7)).thenThrow(refusal);
            AiSkillPlanRunner scoped = new AiSkillPlanRunner(persistenceService, mock(AiAssistantToolExecutor.class),
                scopeReadService, mock(AiAssistantWorkBriefReadService.class),
                new AiAssistantPromptAssembler(objectMapper, new AiAssistantToolCatalog()),
                mock(AiChatRealtimeDispatcher.class), workspaceService, objectMapper, operation.scopes);
            Runnable guard = mock(Runnable.class);
            org.junit.jupiter.api.Assertions.assertSame(refusal,
                assertThrows(ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException.class,
                    () -> scoped.run(TURN, routing("activity_digest_v1"), AiChatQueryScope.none(),
                        new AiChatResourceRegistry(), 16_384, guard)));
            org.mockito.Mockito.verifyNoInteractions(guard, persistenceService, scopeReadService);
        }
    }

    private AiSkillRouter.Routing routing(String key) {
        return new AiSkillRouter.Routing(
                skillCatalog.find(key).orElseThrow(), AiSkillRouter.MATCHED, null, false);
    }
}
