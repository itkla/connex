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

    @BeforeEach
    void setUp() {
        persistenceService = mock(AiChatTurnPersistenceService.class);
        scopeReadService = mock(AiAssistantScopeReadService.class);
        workspaceService = mock(WorkspaceService.class);
        when(workspaceService.permissionsFor(WORKSPACE_ID, USER_ID))
                .thenReturn(Set.of(Permission.AI_USE));
        when(persistenceService.proposeTool(any(), anyInt(), anyString(), anyString()))
                .thenReturn(88);
        runner = new AiSkillPlanRunner(
                persistenceService,
                mock(AiAssistantToolExecutor.class),
                scopeReadService,
                mock(AiAssistantWorkBriefReadService.class),
                new AiAssistantPromptAssembler(objectMapper, new AiAssistantToolCatalog()),
                mock(AiChatRealtimeDispatcher.class),
                workspaceService,
                objectMapper);
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
                any(), anyInt(), anyString(), anyString());
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
                mock(AiChatRealtimeDispatcher.class), workspaceService, objectMapper);
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

    private AiSkillRouter.Routing routing(String key) {
        return new AiSkillRouter.Routing(
                skillCatalog.find(key).orElseThrow(), AiSkillRouter.MATCHED, null, false);
    }
}
