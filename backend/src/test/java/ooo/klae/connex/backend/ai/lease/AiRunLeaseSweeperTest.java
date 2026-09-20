package ooo.klae.connex.backend.ai.lease;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.beans.AiRunLeaseRow;
import ooo.klae.connex.backend.mappers.AiRunLeaseMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.services.PlacementRegistry;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Proves the lease sweep stays a bounded, subject-agnostic pass.
 *
 * <p>The budget and cursor assertions are the ones that matter for the advertised detection bound:
 * an unpaginated enumeration would settle every workspace in one pass and fail them.
 *
 * <p>The reap assertions matter for a different reason. The reap is what keeps {@code ai_run_lease}
 * from growing one permanent row per assistant turn ever run, and a healthy workspace — the common
 * case — never appears on the expired-lease page at all, so a reap driven off that page would never
 * visit it. The reap therefore has its own enumeration, its own cursor, and no dependence on the
 * settlement budget, and each of those three is asserted rather than described.
 */
class AiRunLeaseSweeperTest {

    private static final String FOREIGN_CATALOG = "cnx_foreign";
    private static final int FIRST_WORKSPACE_ID = 11;
    private static final int SECOND_WORKSPACE_ID = 22;
    private static final int TOMBSTONED_WORKSPACE_ID = 33;
    private static final int RETENTION_SECONDS = 3600;

    private AiRunLeaseService leaseService;
    private AiRunLeaseMapper leaseMapper;
    private PlacementRegistry placementRegistry;
    private RecordingTenantWorkScope tenantWorkScope;
    private JobRunRecorder jobRunRecorder;
    private AiProperties properties;

    @BeforeEach
    void setUp() {
        leaseService = mock(AiRunLeaseService.class);
        leaseMapper = mock(AiRunLeaseMapper.class);
        placementRegistry = mock(PlacementRegistry.class);
        tenantWorkScope = new RecordingTenantWorkScope();
        jobRunRecorder = mock(JobRunRecorder.class);
        properties = new AiProperties();
    }

    @Test
    void everyActiveCatalogIsVisitedAndTheStartingCatalogRotatesBetweenPasses() {
        when(placementRegistry.activeCatalogs()).thenReturn(Arrays.asList(null, FOREIGN_CATALOG));
        when(leaseMapper.workspaceIdsWithExpiredLeases(anyInt(), anyInt())).thenReturn(List.of());
        when(leaseMapper.workspaceIdsWithReapableTombstones(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of());
        AiRunLeaseSweeper sweeper = sweeper(handler(AiRunLeaseSubject.CHAT_TURN));

        sweeper.sweep();
        sweeper.sweep();

        assertEquals(
                Arrays.asList(
                        null, null, FOREIGN_CATALOG, FOREIGN_CATALOG,
                        FOREIGN_CATALOG, FOREIGN_CATALOG, null, null),
                tenantWorkScope.catalogs);
    }

    @Test
    void theWorkspaceCursorAdvancesAcrossPassesAndWrapsWhenThePageRunsOut() {
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(leaseMapper.workspaceIdsWithExpiredLeases(0, 50))
                .thenReturn(List.of(FIRST_WORKSPACE_ID, SECOND_WORKSPACE_ID));
        when(leaseMapper.workspaceIdsWithExpiredLeases(SECOND_WORKSPACE_ID, 50))
                .thenReturn(List.of());
        when(leaseMapper.findExpiredLeases(anyInt(), any(), anyInt())).thenReturn(List.of());
        AiRunLeaseSweeper sweeper = sweeper(handler(AiRunLeaseSubject.CHAT_TURN));

        sweeper.sweep();
        sweeper.sweep();

        verify(leaseMapper, times(2)).workspaceIdsWithExpiredLeases(0, 50);
        verify(leaseMapper).workspaceIdsWithExpiredLeases(SECOND_WORKSPACE_ID, 50);
    }

    @Test
    void onePassNeverExaminesMoreLeasesThanItsGlobalBudget() {
        properties.setRunLeaseSweepMaxSettlements(3);
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(leaseMapper.workspaceIdsWithExpiredLeases(anyInt(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID, SECOND_WORKSPACE_ID));
        when(leaseMapper.findExpiredLeases(eq(FIRST_WORKSPACE_ID), any(), anyInt()))
                .thenReturn(List.of(row(FIRST_WORKSPACE_ID, 1L), row(FIRST_WORKSPACE_ID, 2L)));
        when(leaseMapper.findExpiredLeases(eq(SECOND_WORKSPACE_ID), any(), anyInt()))
                .thenReturn(List.of(row(SECOND_WORKSPACE_ID, 3L), row(SECOND_WORKSPACE_ID, 4L)));
        RecordingSubjectHandler handler = handler(AiRunLeaseSubject.CHAT_TURN);
        AiRunLeaseSweeper sweeper = sweeper(handler);

        sweeper.sweep();

        assertEquals(3, handler.settled.size());
    }

    @Test
    void theExpiredLeaseBatchNeverExceedsTheConfiguredPageSize() {
        properties.setRunLeaseSweepBatch(7);
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(leaseMapper.workspaceIdsWithExpiredLeases(anyInt(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID));
        when(leaseMapper.findExpiredLeases(anyInt(), any(), anyInt())).thenReturn(List.of());
        AiRunLeaseSweeper sweeper = sweeper(handler(AiRunLeaseSubject.CHAT_TURN));

        sweeper.sweep();

        verify(leaseMapper).findExpiredLeases(
                FIRST_WORKSPACE_ID, List.of(AiRunLeaseSubject.CHAT_TURN.wireKey()), 7);
    }

    /**
     * A lease whose subject kind no handler on this binary owns must not even be discovered. It
     * can neither be settled nor safely retired — an owner of that kind may still be alive on
     * another binary, and retiring it would let the reap delete the row and restart that key's
     * fencing epoch underneath it — so a discovery that returned it would let it hold the head of
     * an oldest-first page forever, spending the settlement budget and pushing genuinely dead
     * owners out of reach.
     */
    @Test
    void discoveryAsksOnlyForTheSubjectKindsThisInstanceCanSettle() {
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(leaseMapper.workspaceIdsWithExpiredLeases(anyInt(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID));
        when(leaseMapper.findExpiredLeases(anyInt(), any(), anyInt())).thenReturn(List.of());
        AiRunLeaseSweeper sweeper = sweeper(handler(AiRunLeaseSubject.CHAT_TURN));

        sweeper.sweep();

        verify(leaseMapper).findExpiredLeases(
                eq(FIRST_WORKSPACE_ID),
                eq(List.of(AiRunLeaseSubject.CHAT_TURN.wireKey())),
                anyInt());
    }

    @Test
    void anInstanceWithNoHandlerAtAllDiscoversNoLeasesAndStillReaps() {
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(leaseMapper.workspaceIdsWithReapableTombstones(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(TOMBSTONED_WORKSPACE_ID));
        AiRunLeaseSweeper sweeper = sweeper();

        sweeper.sweep();

        verify(leaseMapper, never()).workspaceIdsWithExpiredLeases(anyInt(), anyInt());
        verify(leaseMapper, never()).findExpiredLeases(anyInt(), any(), anyInt());
        verify(leaseService).reapTombstones(TOMBSTONED_WORKSPACE_ID, RETENTION_SECONDS, 50);
    }

    @Test
    void oneLeaseThatThrowsIsCountedAndTheRestOfTheWorkspaceStillSettles() {
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(leaseMapper.workspaceIdsWithExpiredLeases(anyInt(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID));
        when(leaseMapper.findExpiredLeases(anyInt(), any(), anyInt()))
                .thenReturn(List.of(row(FIRST_WORKSPACE_ID, 1L), row(FIRST_WORKSPACE_ID, 2L)));
        RecordingSubjectHandler handler = handler(AiRunLeaseSubject.CHAT_TURN);
        handler.failOnSubjectId = 1L;
        AiRunLeaseSweeper sweeper = sweeper(handler);

        sweeper.sweep();

        assertEquals(List.of(2L), handler.settled);
        assertEquals(
                Map.of(
                        "phase", "lease_settlement",
                        "visitedCount", 2,
                        "expiredCount", 1,
                        "failedCount", 1),
                countsOf(recordedDetail(JobRunStatus.FAILED)));
    }

    @Test
    void settlementDispatchesBySubjectKindSoAFutureAgentRunInheritsThisSweeper() {
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(leaseMapper.workspaceIdsWithExpiredLeases(anyInt(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID));
        AiRunLeaseRow agentRun = row(FIRST_WORKSPACE_ID, 9L);
        agentRun.setSubjectKind(AiRunLeaseSubject.AGENT_RUN.wireKey());
        when(leaseMapper.findExpiredLeases(anyInt(), any(), anyInt()))
                .thenReturn(List.of(row(FIRST_WORKSPACE_ID, 1L), agentRun));
        RecordingSubjectHandler chatTurns = handler(AiRunLeaseSubject.CHAT_TURN);
        RecordingSubjectHandler agentRuns = handler(AiRunLeaseSubject.AGENT_RUN);
        AiRunLeaseSweeper sweeper = sweeper(chatTurns, agentRuns);

        sweeper.sweep();

        assertEquals(List.of(1L), chatTurns.settled);
        assertEquals(List.of(9L), agentRuns.settled);
        assertEquals(AiRunLeaseSubject.AGENT_RUN, agentRuns.keys.get(0).subject());
        verify(leaseMapper).findExpiredLeases(
                eq(FIRST_WORKSPACE_ID),
                eq(List.of(
                        AiRunLeaseSubject.CHAT_TURN.wireKey(),
                        AiRunLeaseSubject.AGENT_RUN.wireKey())),
                anyInt());
    }

    /**
     * The defect this pins: the reap used to run only for workspaces the expired-lease page
     * returned, so a workspace whose runs all settled cleanly — every healthy workspace — was never
     * visited and retained one tombstone per run for the life of the tenant.
     */
    @Test
    void theReapVisitsAWorkspaceThatHoldsNoExpiredLeaseAtAll() {
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(leaseMapper.workspaceIdsWithExpiredLeases(anyInt(), anyInt())).thenReturn(List.of());
        when(leaseMapper.workspaceIdsWithReapableTombstones(0, RETENTION_SECONDS, 50))
                .thenReturn(List.of(TOMBSTONED_WORKSPACE_ID));
        when(leaseService.reapTombstones(TOMBSTONED_WORKSPACE_ID, RETENTION_SECONDS, 50))
                .thenReturn(4);
        AiRunLeaseSweeper sweeper = sweeper(handler(AiRunLeaseSubject.CHAT_TURN));

        sweeper.sweep();

        verify(leaseService).reapTombstones(TOMBSTONED_WORKSPACE_ID, RETENTION_SECONDS, 50);
        assertEquals(List.of(TOMBSTONED_WORKSPACE_ID), tenantWorkScope.workspaceIds);
        JobRunDetail detail = recordedDetail(JobRunStatus.SUCCEEDED);
        assertEquals("tombstone_reap", detail.metadata().get("phase"));
        assertEquals(4, detail.metadata().get("deletedCount"));
        assertTrue(detail.metadata().containsKey("durationMs"));
    }

    @Test
    void theReapStillRunsWhenTheSettlementBudgetIsAlreadySpent() {
        properties.setRunLeaseSweepMaxSettlements(1);
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(leaseMapper.workspaceIdsWithExpiredLeases(anyInt(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID));
        when(leaseMapper.findExpiredLeases(anyInt(), any(), anyInt()))
                .thenReturn(List.of(row(FIRST_WORKSPACE_ID, 1L)));
        when(leaseMapper.workspaceIdsWithReapableTombstones(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(TOMBSTONED_WORKSPACE_ID));
        AiRunLeaseSweeper sweeper = sweeper(handler(AiRunLeaseSubject.CHAT_TURN));

        sweeper.sweep();

        verify(leaseService).reapTombstones(TOMBSTONED_WORKSPACE_ID, RETENTION_SECONDS, 50);
    }

    @Test
    void theReapCursorAdvancesAcrossPassesAndWrapsWhenThePageRunsOut() {
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(leaseMapper.workspaceIdsWithExpiredLeases(anyInt(), anyInt())).thenReturn(List.of());
        when(leaseMapper.workspaceIdsWithReapableTombstones(0, RETENTION_SECONDS, 50))
                .thenReturn(List.of(FIRST_WORKSPACE_ID, SECOND_WORKSPACE_ID));
        when(leaseMapper.workspaceIdsWithReapableTombstones(
                SECOND_WORKSPACE_ID, RETENTION_SECONDS, 50))
                .thenReturn(List.of());
        AiRunLeaseSweeper sweeper = sweeper(handler(AiRunLeaseSubject.CHAT_TURN));

        sweeper.sweep();
        sweeper.sweep();

        verify(leaseMapper, times(2)).workspaceIdsWithReapableTombstones(0, RETENTION_SECONDS, 50);
        verify(leaseMapper)
                .workspaceIdsWithReapableTombstones(SECOND_WORKSPACE_ID, RETENTION_SECONDS, 50);
    }

    @Test
    void aWorkspaceWithNothingToDoRecordsNoJobRun() {
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(leaseMapper.workspaceIdsWithExpiredLeases(anyInt(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID));
        when(leaseMapper.findExpiredLeases(anyInt(), any(), anyInt())).thenReturn(List.of());
        when(leaseMapper.workspaceIdsWithReapableTombstones(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID));
        AiRunLeaseSweeper sweeper = sweeper(handler(AiRunLeaseSubject.CHAT_TURN));

        sweeper.sweep();

        verify(jobRunRecorder, never()).record(
                eq(JobRunRecorder.AI_RUN_LEASE_SWEEP), anyInt(), any(JobRunStatus.class), any());
    }

    @Test
    void everySettlementRunsPinnedToItsOwnWorkspace() {
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(leaseMapper.workspaceIdsWithExpiredLeases(anyInt(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID, SECOND_WORKSPACE_ID));
        when(leaseMapper.findExpiredLeases(anyInt(), any(), anyInt())).thenReturn(List.of());
        AiRunLeaseSweeper sweeper = sweeper(handler(AiRunLeaseSubject.CHAT_TURN));

        sweeper.sweep();

        assertEquals(
                List.of(FIRST_WORKSPACE_ID, SECOND_WORKSPACE_ID), tenantWorkScope.workspaceIds);
    }

    /**
     * {@code job_run} is tenant-scoped, and the tenant backstop is fail-closed off the request
     * thread. A record written after the workspace scope closed is refused under
     * catalog-per-placement routing and swallowed by the recorder as a warning, so the sweep
     * evidence the advertised detection bound rests on would not exist at all.
     */
    @Test
    void everyJobRunIsRecordedInsideTheWorkspaceScope() {
        List<Boolean> recordedInsideScope = new ArrayList<>();
        doAnswer(invocation -> {
            recordedInsideScope.add(tenantWorkScope.insideWorkspace());
            return null;
        }).when(jobRunRecorder).record(anyString(), anyInt(), any(), any());
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(leaseMapper.workspaceIdsWithExpiredLeases(anyInt(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID));
        when(leaseMapper.findExpiredLeases(anyInt(), any(), anyInt()))
                .thenReturn(List.of(row(FIRST_WORKSPACE_ID, 1L)));
        when(leaseMapper.workspaceIdsWithReapableTombstones(anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(TOMBSTONED_WORKSPACE_ID));
        when(leaseService.reapTombstones(eq(TOMBSTONED_WORKSPACE_ID), anyInt(), anyInt()))
                .thenReturn(2);
        AiRunLeaseSweeper sweeper = sweeper(handler(AiRunLeaseSubject.CHAT_TURN));

        sweeper.sweep();

        assertEquals(List.of(true, true), recordedInsideScope);
    }

    @Test
    void aWorkspaceThatThrowsRecordsItsFailureInsideTheWorkspaceScope() {
        List<Boolean> recordedInsideScope = new ArrayList<>();
        doAnswer(invocation -> {
            recordedInsideScope.add(tenantWorkScope.insideWorkspace());
            return null;
        }).when(jobRunRecorder).record(anyString(), anyInt(), any(), any());
        when(placementRegistry.activeCatalogs()).thenReturn(List.of(FOREIGN_CATALOG));
        when(leaseMapper.workspaceIdsWithExpiredLeases(anyInt(), anyInt()))
                .thenReturn(List.of(FIRST_WORKSPACE_ID));
        when(leaseMapper.findExpiredLeases(anyInt(), any(), anyInt()))
                .thenThrow(new IllegalStateException("discovery failed"));
        AiRunLeaseSweeper sweeper = sweeper(handler(AiRunLeaseSubject.CHAT_TURN));

        sweeper.sweep();

        assertEquals(List.of(true), recordedInsideScope);
    }

    private AiRunLeaseSweeper sweeper(AiRunLeaseSubjectHandler... handlers) {
        return new AiRunLeaseSweeper(
                leaseService,
                leaseMapper,
                placementRegistry,
                tenantWorkScope,
                jobRunRecorder,
                properties,
                List.of(handlers));
    }

    private JobRunDetail recordedDetail(JobRunStatus status) {
        ArgumentCaptor<JobRunDetail> detail = ArgumentCaptor.forClass(JobRunDetail.class);
        verify(jobRunRecorder).record(
                eq(JobRunRecorder.AI_RUN_LEASE_SWEEP), anyInt(), eq(status), detail.capture());
        return detail.getValue();
    }

    private static Map<String, Object> countsOf(JobRunDetail detail) {
        Map<String, Object> counts = new LinkedHashMap<>(detail.metadata());
        counts.remove("durationMs");
        return counts;
    }

    private static AiRunLeaseRow row(int workspaceId, long subjectId) {
        AiRunLeaseRow row = new AiRunLeaseRow();
        row.setWorkspaceId(workspaceId);
        row.setSubjectKind(AiRunLeaseSubject.CHAT_TURN.wireKey());
        row.setSubjectId(subjectId);
        row.setOwner("11111111-2222-3333-4444-555555555555");
        row.setEpoch(subjectId + 1L);
        return row;
    }

    private static RecordingSubjectHandler handler(AiRunLeaseSubject subject) {
        return new RecordingSubjectHandler(subject);
    }

    private static final class RecordingSubjectHandler implements AiRunLeaseSubjectHandler {
        private final AiRunLeaseSubject subject;
        private final List<Long> settled = new ArrayList<>();
        private final List<AiRunLeaseKey> keys = new ArrayList<>();
        private Long failOnSubjectId;

        private RecordingSubjectHandler(AiRunLeaseSubject subject) {
            this.subject = subject;
        }

        @Override
        public AiRunLeaseSubject subject() {
            return subject;
        }

        @Override
        public boolean isSubjectRunning(int workspaceId, long subjectId) {
            return true;
        }

        @Override
        public boolean settleOrphan(AiRunLeaseKey key, long expectedEpoch) {
            if (failOnSubjectId != null && failOnSubjectId == key.subjectId()) {
                throw new IllegalStateException("settlement failed");
            }
            keys.add(key);
            settled.add(key.subjectId());
            return true;
        }
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
