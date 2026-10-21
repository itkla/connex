package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import ooo.klae.connex.backend.ai.AiGenerationContextRunner;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiChatAgentLoopService;
import ooo.klae.connex.backend.ai.assistant.AiChatQueuedTurn;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.tenant.ControlPlaneRead;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.ObjectMapper;

/** Keeps snapshot refusals intact through mission settlement and scheduler isolation. */
class AiAgentScopeFailureTest {
    @ParameterizedTest
    @ValueSource(strings = {"snapshot", "missing"})
    void missionInterruptsItsClaimedGenerationAndRethrowsTheSameSnapshotRefusal(String kind) {
        var context = mock(AiGenerationContextRunner.class);
        var gate = mock(AiAgentCapabilityGate.class);
        var persistence = mock(AiAgentRunPersistenceService.class);
        var loop = mock(AiChatAgentLoopService.class);
        var restriction = mock(AiRestrictionEpoch.class);
        var runs = mock(AiAgentRunMapper.class);
        var candidate = candidate();
        var turn = new AiChatQueuedTurn(7, 11, 13, 17, 19, 1, 3, false, List.of(), List.of());
        var policy = new AiAgentExecutionPolicy(31, 1, 2L, turn, null, null, 8,
                null, null, null, persistence, gate);
        var refusal = refusal(kind);
        doAnswer(call -> {
            call.getArgument(3, Runnable.class).run();
            return null;
        }).when(context).run(eq(7), eq(11), any(Locale.class), any(Runnable.class));
        doAnswer(call -> {
            call.getArgument(2, Runnable.class).run();
            return null;
        }).when(restriction).runWithExpectedEgressEpoch(anyInt(), anyLong(), any(Runnable.class));
        when(gate.requireReady()).thenReturn(new byte[0]);
        when(persistence.claim(eq(candidate), any(), any(), eq(gate))).thenReturn(policy);
        when(runs.getRun(7, 31)).thenReturn(candidate);
        when(loop.run(turn)).thenThrow(refusal);
        var runner = new AiAgentSegmentRunner(context, gate, mock(AiAgentRunService.class), persistence,
                loop, new AiProperties(), restriction, runs, mock(AiAgentRealtimePublisher.class), new ObjectMapper());

        assertSame(refusal, assertThrows(TenantScopeSnapshotException.class, () -> runner.run(candidate)));

        verify(loop).run(turn);
        verify(persistence).interrupt(candidate, "provider_failed");
        assertNull(AiAgentExecutionPolicy.current());
    }

    @ParameterizedTest
    @ValueSource(strings = {"snapshot", "missing"})
    void schedulerRecordsTheFailedWorkspaceAndDoesNotDeferSnapshotRefusals(String kind) {
        var workspaces = mock(WorkspaceMapper.class);
        var scope = mock(TenantWorkScope.class);
        var runs = mock(AiAgentRunMapper.class);
        var runner = mock(AiAgentSegmentRunner.class);
        var initializer = mock(AiAgentPrivacyInitializer.class);
        var properties = new AiProperties();
        properties.setMissionsEnabled(true);
        var persistence = mock(AiAgentRunPersistenceService.class);
        var jobs = mock(JobRunRecorder.class);
        var candidate = candidate();
        var refusal = refusal(kind);
        when(scope.unroutedRead(any())).thenAnswer(call -> call.getArgument(0, ControlPlaneRead.class).read());
        when(workspaces.findWorkspaceIdsPage(0, 100)).thenReturn(List.of(7));
        when(initializer.isReady()).thenReturn(true);
        when(scope.inWorkspace(eq(7), any(java.util.function.Supplier.class)))
                .thenAnswer(call -> call.getArgument(1, java.util.function.Supplier.class).get());
        when(runs.dueRuns(7, 1)).thenReturn(List.of(candidate));
        doThrow(refusal).when(runner).run(candidate);
        var scheduler = new AiAgentScheduler(workspaces, scope, runs, runner, initializer, properties,
                persistence, mock(AiAgentRealtimePublisher.class), jobs);
        try {
            assertSame(refusal, assertThrows(TenantScopeSnapshotException.class,
                    () -> ReflectionTestUtils.invokeMethod(scheduler, "sweepPage")));

            verify(persistence, never()).defer(candidate);
            verify(jobs).record(eq(JobRunRecorder.AI_AGENT_SCHEDULER), eq(7), eq(JobRunStatus.FAILED), any());
        } finally {
            scheduler.shutdown();
        }
    }

    private static AiAgentRun candidate() {
        var candidate = new AiAgentRun();
        candidate.setId(31);
        candidate.setWorkspaceId(7);
        candidate.setSessionId(13);
        candidate.setOwnerUserId(11);
        candidate.setLocale("en");
        candidate.setMissionType("record_review_v1");
        candidate.setDefinitionVersion(1);
        candidate.setRuntimeContractVersion(1);
        candidate.setExecutionGeneration(2L);
        candidate.setScopeJson("{\"kind\":\"record\",\"recordKind\":\"person\",\"recordId\":41}");
        return candidate;
    }

    private static TenantScopeSnapshotException refusal(String kind) {
        return kind.equals("missing") ? new TenantScopeSnapshotMissingException("missing payload")
                : new TenantScopeSnapshotException("scope mismatch");
    }
}
