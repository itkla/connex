package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.beans.AiChatTurnRef;
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
 *
 * <p>The other two are about cost and evidence. The pass carries the same global per-pass budget
 * the lease sweep does, so a backlog in one tenant cannot monopolise the shared scheduler thread
 * and silently widen the detection bound for every other tenant; and it records its job runs inside
 * the workspace scope, without which the tenant backstop refuses the write under catalog routing
 * and the evidence simply never exists.
 */
class AiChatTurnLifetimeSweeperTest {

    private static final String FOREIGN_CATALOG = "cnx_foreign";
    private static final int FIRST_WORKSPACE_ID = 11;
    private static final int SECOND_WORKSPACE_ID = 22;
    private static final int LIFETIME_SECONDS = 185;

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
                properties);
    }

    /**
     * Every staleness boundary this pass applies is the database's, expressed as the lifetime in
     * seconds rather than as an instant this JVM computed. The column it is compared against,
     * {@code updated_at}, is written by MySQL, and this pass runs unattended against every
     * workspace the instance routes to — so an instance whose clock ran ahead of the database would
     * settle live turns estate-wide rather than only in the session a reader had open.
     */
    @Test
    void everyStalenessBoundaryIsTheDatabasesAndNotThisJvmsClock() {
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(chatMapper.workspaceIdsWithUnleasedStaleTurns(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID));
        when(chatMapper.findUnleasedStaleTurnRefs(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(new AiChatTurnRef(3, 41)));

        sweeper.sweep();

        verify(chatMapper).workspaceIdsWithUnleasedStaleTurns(0, LIFETIME_SECONDS, 50);
        verify(chatMapper).findUnleasedStaleTurnRefs(FIRST_WORKSPACE_ID, LIFETIME_SECONDS, 50);
        verify(persistenceService)
                .expireUnleasedTurn(FIRST_WORKSPACE_ID, 3, 41, LIFETIME_SECONDS);
    }

    @Test
    void theExpiryUsesTheDurableTimeoutVocabularyAndNeverAnOwnershipLoss() {
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(chatMapper.workspaceIdsWithUnleasedStaleTurns(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID));
        when(chatMapper.findUnleasedStaleTurnRefs(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(new AiChatTurnRef(3, 41)));

        sweeper.sweep();

        verify(persistenceService)
                .expireUnleasedTurn(eq(FIRST_WORKSPACE_ID), eq(3), eq(41), anyInt());
        verify(persistenceService, never()).settleOrphanedTurn(any(), anyString(), anyString());
        verify(persistenceService, never()).markTerminal(any(), anyString(), anyString());
    }

    @Test
    void aWorkspaceWithNoStaleUnleasedTurnSettlesNothingAndRecordsNothing() {
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(chatMapper.workspaceIdsWithUnleasedStaleTurns(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID));
        when(chatMapper.findUnleasedStaleTurnRefs(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of());

        sweeper.sweep();

        verify(persistenceService, never())
                .expireUnleasedTurn(anyInt(), anyInt(), anyInt(), anyInt());
        verify(jobRunRecorder, never()).record(
                eq(JobRunRecorder.AI_CHAT_TURN_LIFETIME_SWEEP), anyInt(), any(), any());
    }

    /**
     * The global budget the lease sweep has, which this pass previously lacked. Without it one
     * pass issues catalogs × workspaces × batch locking transactions on the one shared scheduler
     * thread, and a backlog in a single tenant delays every other tenant's detection past the
     * advertised bound.
     */
    @Test
    void onePassNeverExpiresMoreTurnsThanItsGlobalBudget() {
        properties.setRunLeaseSweepMaxSettlements(3);
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(chatMapper.workspaceIdsWithUnleasedStaleTurns(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID, SECOND_WORKSPACE_ID));
        when(chatMapper.findUnleasedStaleTurnRefs(eq(FIRST_WORKSPACE_ID), anyInt(), anyInt()))
                .thenReturn(List.of(new AiChatTurnRef(3, 41), new AiChatTurnRef(3, 42)));
        when(chatMapper.findUnleasedStaleTurnRefs(eq(SECOND_WORKSPACE_ID), anyInt(), anyInt()))
                .thenReturn(List.of(new AiChatTurnRef(4, 43), new AiChatTurnRef(4, 44)));

        sweeper.sweep();

        verify(persistenceService, times(3))
                .expireUnleasedTurn(anyInt(), anyInt(), anyInt(), anyInt());
        verify(persistenceService, never())
                .expireUnleasedTurn(eq(SECOND_WORKSPACE_ID), eq(4), eq(44), anyInt());
    }

    @Test
    void theBatchNeverExceedsTheRemainingBudget() {
        properties.setRunLeaseSweepMaxSettlements(2);
        properties.setRunLeaseSweepBatch(50);
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(chatMapper.workspaceIdsWithUnleasedStaleTurns(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID));
        when(chatMapper.findUnleasedStaleTurnRefs(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of());

        sweeper.sweep();

        verify(chatMapper).findUnleasedStaleTurnRefs(FIRST_WORKSPACE_ID, LIFETIME_SECONDS, 2);
    }

    /**
     * {@code job_run} is tenant-scoped, and the tenant backstop is fail-closed off the request
     * thread. Recording after the workspace scope closed is refused under catalog-per-placement
     * routing and swallowed by the recorder as a warning, so the sweep evidence would simply not
     * exist in the deployment that most needs it.
     */
    @Test
    void theJobRunIsRecordedInsideTheWorkspaceScope() {
        List<Boolean> recordedInsideScope = new ArrayList<>();
        doAnswer(invocation -> {
            recordedInsideScope.add(tenantWorkScope.insideWorkspace());
            return null;
        }).when(jobRunRecorder).record(anyString(), anyInt(), any(), any());
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(chatMapper.workspaceIdsWithUnleasedStaleTurns(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID));
        when(chatMapper.findUnleasedStaleTurnRefs(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(new AiChatTurnRef(3, 41)));

        sweeper.sweep();

        assertEquals(List.of(true), recordedInsideScope);
    }

    @Test
    void aWorkspaceThatThrowsRecordsItsFailureInsideTheWorkspaceScope() {
        List<Boolean> recordedInsideScope = new ArrayList<>();
        doAnswer(invocation -> {
            recordedInsideScope.add(tenantWorkScope.insideWorkspace());
            return null;
        }).when(jobRunRecorder).record(anyString(), anyInt(), any(), any());
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(chatMapper.workspaceIdsWithUnleasedStaleTurns(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID));
        when(chatMapper.findUnleasedStaleTurnRefs(anyInt(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("discovery failed"));

        sweeper.sweep();

        assertEquals(List.of(true), recordedInsideScope);
    }

    @Test
    void oneTurnThatThrowsIsCountedAndTheRestOfTheWorkspaceStillExpires() {
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(chatMapper.workspaceIdsWithUnleasedStaleTurns(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID));
        when(chatMapper.findUnleasedStaleTurnRefs(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(new AiChatTurnRef(3, 41), new AiChatTurnRef(3, 42)));
        when(persistenceService.expireUnleasedTurn(
                eq(FIRST_WORKSPACE_ID), eq(3), eq(41), anyInt()))
                .thenThrow(new IllegalStateException("expiry failed"));
        when(persistenceService.expireUnleasedTurn(
                eq(FIRST_WORKSPACE_ID), eq(3), eq(42), anyInt()))
                .thenReturn(true);

        sweeper.sweep();

        verify(persistenceService)
                .expireUnleasedTurn(eq(FIRST_WORKSPACE_ID), eq(3), eq(42), anyInt());
        JobRunDetail detail = recordedDetail(JobRunStatus.FAILED);
        assertEquals(2, detail.metadata().get("visitedCount"));
        assertEquals(1, detail.metadata().get("expiredCount"));
        assertEquals(1, detail.metadata().get("failedCount"));
        assertTrue(detail.metadata().containsKey("durationMs"));
    }

    @Test
    void everyActiveCatalogIsVisitedAndTheStartingCatalogRotatesBetweenPasses() {
        when(placementRegistry.activeCatalogs()).thenReturn(Arrays.asList(null, FOREIGN_CATALOG));
        when(chatMapper.workspaceIdsWithUnleasedStaleTurns(anyInt(), anyInt(), anyInt()))
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
        when(chatMapper.workspaceIdsWithUnleasedStaleTurns(eq(0), anyInt(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID, SECOND_WORKSPACE_ID));
        when(chatMapper.workspaceIdsWithUnleasedStaleTurns(
                eq(SECOND_WORKSPACE_ID), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(chatMapper.findUnleasedStaleTurnRefs(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of());

        sweeper.sweep();
        sweeper.sweep();

        verify(chatMapper, times(2)).workspaceIdsWithUnleasedStaleTurns(eq(0), anyInt(), anyInt());
        verify(chatMapper)
                .workspaceIdsWithUnleasedStaleTurns(eq(SECOND_WORKSPACE_ID), anyInt(), anyInt());
        assertEquals(
                List.of(
                        FIRST_WORKSPACE_ID, SECOND_WORKSPACE_ID,
                        FIRST_WORKSPACE_ID, SECOND_WORKSPACE_ID),
                tenantWorkScope.workspaceIds);
    }

    @Test
    void aWorkspaceThatThrowsIsRecordedAndTheNextWorkspaceStillRuns() {
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(chatMapper.workspaceIdsWithUnleasedStaleTurns(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID, SECOND_WORKSPACE_ID));
        when(chatMapper.findUnleasedStaleTurnRefs(eq(FIRST_WORKSPACE_ID), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("discovery failed"));
        when(chatMapper.findUnleasedStaleTurnRefs(eq(SECOND_WORKSPACE_ID), anyInt(), anyInt()))
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

    private static final class RecordingTenantWorkScope extends TenantWorkScope {
        private final List<String> catalogs = new ArrayList<>();
        private final List<Integer> workspaceIds = new ArrayList<>();
        private int workspaceDepth;

        private RecordingTenantWorkScope() {
            super(
                    new TenantContext(),
                    mock(TenantCatalogResolver.class),
                    mock(WorkspaceMapper.class));
        }

        private boolean insideWorkspace() {
            return workspaceDepth > 0;
        }

        @Override
        public <T> T withCatalog(String catalog, Supplier<T> work) {
            catalogs.add(catalog);
            return work.get();
        }

        @Override
        public void inWorkspace(int workspaceId, Runnable work) {
            workspaceIds.add(workspaceId);
            workspaceDepth++;
            try {
                work.run();
            } finally {
                workspaceDepth--;
            }
        }

        @Override
        public <T> T inWorkspace(int workspaceId, Supplier<T> work) {
            workspaceIds.add(workspaceId);
            workspaceDepth++;
            try {
                return work.get();
            } finally {
                workspaceDepth--;
            }
        }
    }
}
