package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.services.PlacementRegistry;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Proves the unleased absolute-lifetime pass stays the rolling-deploy half of the recovery
 * guarantee.
 *
 * <p>The load-bearing assertion is the one about vocabulary: this pass settles with the durable
 * expiry the product has always used and never with an ownership loss, because a turn it reaches
 * is one no instance ever recorded ownership of. Merging it into the lease sweeper would break
 * that.
 */
class AiChatTurnLifetimeSweeperTest {

    private static final String FOREIGN_CATALOG = "cnx_foreign";
    private static final int FIRST_WORKSPACE_ID = 11;
    private static final int SECOND_WORKSPACE_ID = 22;
    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");

    private AiChatMapper chatMapper;
    private AiChatTurnPersistenceService persistenceService;
    private PlacementRegistry placementRegistry;
    private RecordingTenantWorkScope tenantWorkScope;
    private JobRunRecorder jobRunRecorder;
    private AiProperties properties;
    private AiChatTurnLifetimeSweeper sweeper;

    @BeforeEach
    void setUp() {
        chatMapper = mock(AiChatMapper.class);
        persistenceService = mock(AiChatTurnPersistenceService.class);
        placementRegistry = mock(PlacementRegistry.class);
        tenantWorkScope = new RecordingTenantWorkScope();
        jobRunRecorder = mock(JobRunRecorder.class);
        properties = new AiProperties();
        sweeper = new AiChatTurnLifetimeSweeper(
                chatMapper,
                persistenceService,
                placementRegistry,
                tenantWorkScope,
                jobRunRecorder,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void theCutoffIsTheAbsoluteTurnLifetimeBehindTheCurrentInstant() {
        LocalDateTime expected = LocalDateTime.ofInstant(
                NOW.minus(AiAssistantTurnBudget.DURABLE_LIFETIME), ZoneOffset.UTC);
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(chatMapper.workspaceIdsWithUnleasedStaleTurns(anyInt(), any(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID));
        when(chatMapper.findUnleasedStaleTurns(anyInt(), any(), anyInt()))
                .thenReturn(List.of(turn(FIRST_WORKSPACE_ID, 3, 41)));

        sweeper.sweep();

        verify(chatMapper).workspaceIdsWithUnleasedStaleTurns(0, expected, 50);
        verify(chatMapper).findUnleasedStaleTurns(FIRST_WORKSPACE_ID, expected, 50);
        verify(persistenceService).expireUnleasedTurn(FIRST_WORKSPACE_ID, 3, 41, expected);
    }

    @Test
    void theExpiryUsesTheDurableTimeoutVocabularyAndNeverAnOwnershipLoss() {
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(chatMapper.workspaceIdsWithUnleasedStaleTurns(anyInt(), any(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID));
        when(chatMapper.findUnleasedStaleTurns(anyInt(), any(), anyInt()))
                .thenReturn(List.of(turn(FIRST_WORKSPACE_ID, 3, 41)));

        sweeper.sweep();

        verify(persistenceService)
                .expireUnleasedTurn(eq(FIRST_WORKSPACE_ID), eq(3), eq(41), any());
        verify(persistenceService, never()).settleOrphanedTurn(any(), anyString(), anyString());
        verify(persistenceService, never()).markTerminal(any(), anyString(), anyString());
    }

    @Test
    void aWorkspaceWithNoStaleUnleasedTurnSettlesNothingAndRecordsNothing() {
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(chatMapper.workspaceIdsWithUnleasedStaleTurns(anyInt(), any(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID));
        when(chatMapper.findUnleasedStaleTurns(anyInt(), any(), anyInt())).thenReturn(List.of());

        sweeper.sweep();

        verify(persistenceService, never())
                .expireUnleasedTurn(anyInt(), anyInt(), anyInt(), any());
        verify(jobRunRecorder, never()).record(
                eq(JobRunRecorder.AI_CHAT_TURN_LIFETIME_SWEEP), anyInt(), any(), any());
    }

    @Test
    void oneTurnThatThrowsIsCountedAndTheRestOfTheWorkspaceStillExpires() {
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(chatMapper.workspaceIdsWithUnleasedStaleTurns(anyInt(), any(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID));
        when(chatMapper.findUnleasedStaleTurns(anyInt(), any(), anyInt()))
                .thenReturn(List.of(
                        turn(FIRST_WORKSPACE_ID, 3, 41), turn(FIRST_WORKSPACE_ID, 3, 42)));
        when(persistenceService.expireUnleasedTurn(eq(FIRST_WORKSPACE_ID), eq(3), eq(41), any()))
                .thenThrow(new IllegalStateException("expiry failed"));
        when(persistenceService.expireUnleasedTurn(eq(FIRST_WORKSPACE_ID), eq(3), eq(42), any()))
                .thenReturn(true);

        sweeper.sweep();

        verify(persistenceService).expireUnleasedTurn(eq(FIRST_WORKSPACE_ID), eq(3), eq(42), any());
        JobRunDetail detail = recordedDetail(JobRunStatus.FAILED);
        assertEquals(2, detail.metadata().get("visitedCount"));
        assertEquals(1, detail.metadata().get("expiredCount"));
        assertEquals(1, detail.metadata().get("failedCount"));
        assertTrue(detail.metadata().containsKey("durationMs"));
    }

    @Test
    void everyActiveCatalogIsVisitedAndTheStartingCatalogRotatesBetweenPasses() {
        when(placementRegistry.activeCatalogs()).thenReturn(Arrays.asList(null, FOREIGN_CATALOG));
        when(chatMapper.workspaceIdsWithUnleasedStaleTurns(anyInt(), any(), anyInt()))
                .thenReturn(List.of());

        sweeper.sweep();
        sweeper.sweep();

        assertEquals(
                Arrays.asList(null, FOREIGN_CATALOG, FOREIGN_CATALOG, null),
                tenantWorkScope.catalogs);
    }

    @Test
    void theWorkspaceCursorAdvancesAcrossPassesAndWrapsWhenThePageRunsOut() {
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(chatMapper.workspaceIdsWithUnleasedStaleTurns(eq(0), any(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID, SECOND_WORKSPACE_ID));
        when(chatMapper.workspaceIdsWithUnleasedStaleTurns(eq(SECOND_WORKSPACE_ID), any(), anyInt()))
                .thenReturn(List.of());
        when(chatMapper.findUnleasedStaleTurns(anyInt(), any(), anyInt())).thenReturn(List.of());

        sweeper.sweep();
        sweeper.sweep();

        verify(chatMapper, times(2)).workspaceIdsWithUnleasedStaleTurns(eq(0), any(), anyInt());
        verify(chatMapper)
                .workspaceIdsWithUnleasedStaleTurns(eq(SECOND_WORKSPACE_ID), any(), anyInt());
        assertEquals(
                List.of(
                        FIRST_WORKSPACE_ID, SECOND_WORKSPACE_ID,
                        FIRST_WORKSPACE_ID, SECOND_WORKSPACE_ID),
                tenantWorkScope.workspaceIds);
    }

    @Test
    void aWorkspaceThatThrowsIsRecordedAndTheNextWorkspaceStillRuns() {
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(chatMapper.workspaceIdsWithUnleasedStaleTurns(anyInt(), any(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID, SECOND_WORKSPACE_ID));
        when(chatMapper.findUnleasedStaleTurns(eq(FIRST_WORKSPACE_ID), any(), anyInt()))
                .thenThrow(new IllegalStateException("discovery failed"));
        when(chatMapper.findUnleasedStaleTurns(eq(SECOND_WORKSPACE_ID), any(), anyInt()))
                .thenReturn(List.of());

        sweeper.sweep();

        assertEquals(
                List.of(FIRST_WORKSPACE_ID, SECOND_WORKSPACE_ID), tenantWorkScope.workspaceIds);
        verify(jobRunRecorder).record(
                eq(JobRunRecorder.AI_CHAT_TURN_LIFETIME_SWEEP),
                eq(FIRST_WORKSPACE_ID),
                eq(JobRunStatus.FAILED),
                any());
    }

    private JobRunDetail recordedDetail(JobRunStatus status) {
        ArgumentCaptor<JobRunDetail> detail = ArgumentCaptor.forClass(JobRunDetail.class);
        verify(jobRunRecorder).record(
                eq(JobRunRecorder.AI_CHAT_TURN_LIFETIME_SWEEP), anyInt(), eq(status),
                detail.capture());
        return detail.getValue();
    }

    private static AiChatTurn turn(int workspaceId, int sessionId, int turnId) {
        AiChatTurn turn = new AiChatTurn();
        turn.setWorkspaceId(workspaceId);
        turn.setSessionId(sessionId);
        turn.setId(turnId);
        turn.setStatus("running");
        return turn;
    }

    private static final class RecordingTenantWorkScope extends TenantWorkScope {
        private final List<String> catalogs = new ArrayList<>();
        private final List<Integer> workspaceIds = new ArrayList<>();

        private RecordingTenantWorkScope() {
            super(
                    new TenantContext(),
                    mock(TenantCatalogResolver.class),
                    mock(WorkspaceMapper.class));
        }

        @Override
        public <T> T withCatalog(String catalog, Supplier<T> work) {
            catalogs.add(catalog);
            return work.get();
        }

        @Override
        public void inWorkspace(int workspaceId, Runnable work) {
            workspaceIds.add(workspaceId);
            work.run();
        }

        @Override
        public <T> T inWorkspace(int workspaceId, Supplier<T> work) {
            workspaceIds.add(workspaceId);
            return work.get();
        }
    }
}
