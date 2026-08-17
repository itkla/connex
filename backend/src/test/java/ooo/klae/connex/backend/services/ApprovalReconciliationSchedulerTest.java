package ooo.klae.connex.backend.services;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.DocumentApproval;
import ooo.klae.connex.backend.mappers.DocumentApprovalMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class ApprovalReconciliationSchedulerTest {

    @Test
    void schedulerUsesTheConfiguredGateCadenceAndBatchBound() throws ReflectiveOperationException {
        ConditionalOnProperty condition = ApprovalReconciliationScheduler.class
            .getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition);
        assertEquals("connex.approvals", condition.prefix());
        assertArrayEquals(new String[] {"reconciliation-enabled"}, condition.name());
        assertTrue(condition.matchIfMissing());

        Method reconcile = ApprovalReconciliationScheduler.class.getMethod("reconcile");
        Scheduled scheduled = reconcile.getAnnotation(Scheduled.class);
        assertNotNull(scheduled);
        assertEquals("${connex.approvals.reconciliation-delay-ms:900000}",
            scheduled.fixedDelayString());
        assertEquals("${connex.approvals.reconciliation-initial-delay-ms:900000}",
            scheduled.initialDelayString());

        Field batchSize = ApprovalReconciliationScheduler.class.getDeclaredField("batchSize");
        assertEquals("${connex.approvals.reconciliation-batch-size:200}",
            batchSize.getAnnotation(Value.class).value());
    }

    @Test
    void tenantScopeIsInstalledBeforeTheTerminationTransactionOpens()
            throws ReflectiveOperationException {
        Method reconcile = ApprovalReconciliationScheduler.class.getMethod("reconcile");

        assertNull(ApprovalReconciliationScheduler.class.getAnnotation(Transactional.class));
        assertNull(reconcile.getAnnotation(Transactional.class));
        assertNotNull(ApprovalReconciliationScheduler.class
            .getDeclaredField("transactionTemplate"));
    }

    /**
     * The termination methods are package-private so they stay off the RBAC-guarded surface, which
     * also means Spring's proxy-based transaction management would ignore {@code @Transactional} on
     * them. Asserting through Spring's own attribute source keeps that fact from being rediscovered
     * as a missing document lock in production: the scheduler, not the annotation, owns the
     * transaction.
     */
    @Test
    void terminationMethodsCannotRelyOnProxiedTransactionAdvice()
            throws ReflectiveOperationException {
        AnnotationTransactionAttributeSource source = new AnnotationTransactionAttributeSource();
        for (String name : new String[] {
                "terminateIfUnsatisfiable", "invalidateForPolicyChange", "reconcileApproval"}) {
            Method method = java.util.Arrays.stream(
                    DocumentApprovalService.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
            assertFalse(Modifier.isPublic(method.getModifiers()));
            assertNull(source.getTransactionAttribute(method, DocumentApprovalService.class));
        }
    }

    @Test
    void fullBatchesWrapAndRevisitRowsBelowAContinuouslyAdvancingCursor()
            throws ReflectiveOperationException {
        DocumentApprovalMapper mapper = mock(DocumentApprovalMapper.class);
        ApprovalReconciliationScheduler scheduler = scheduler(mapper, mock(DocumentApprovalService.class));
        int maxFullBatches = maxFullBatches();
        when(mapper.findPendingForWorkspace(7, 2)).thenReturn(approvals(1, 2));
        AtomicInteger nextId = new AtomicInteger(100);
        when(mapper.findPendingForWorkspaceAfter(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int first = nextId.getAndAdd(2);
            return approvals(first, first + 1);
        });

        assertEquals(List.of(1, 2), ids(scheduler.selectPendingBatch(7, 2)));
        for (int batch = 1; batch < maxFullBatches; batch++) {
            scheduler.selectPendingBatch(7, 2);
        }

        assertEquals(List.of(1, 2), ids(scheduler.selectPendingBatch(7, 2)));
        verify(mapper, times(2)).findPendingForWorkspace(7, 2);
        verify(mapper, atLeast(1)).findPendingForWorkspaceAfter(anyInt(), anyInt(), anyInt());
    }

    @Test
    void aShortBatchMakesTheFollowingSweepRestartAtZero() {
        DocumentApprovalMapper mapper = mock(DocumentApprovalMapper.class);
        ApprovalReconciliationScheduler scheduler = scheduler(mapper, mock(DocumentApprovalService.class));
        when(mapper.findPendingForWorkspace(7, 2)).thenReturn(approvals(1, 2));
        when(mapper.findPendingForWorkspaceAfter(7, 2, 2)).thenReturn(approvals(3));

        scheduler.selectPendingBatch(7, 2);
        assertEquals(List.of(3), ids(scheduler.selectPendingBatch(7, 2)));
        assertEquals(List.of(1, 2), ids(scheduler.selectPendingBatch(7, 2)));

        verify(mapper, times(2)).findPendingForWorkspace(7, 2);
    }

    @Test
    void aForcedWrapStillCompletesTheCycleBeyondItsHighWaterMark()
            throws ReflectiveOperationException {
        DocumentApprovalMapper mapper = mock(DocumentApprovalMapper.class);
        ApprovalReconciliationScheduler scheduler = scheduler(mapper, mock(DocumentApprovalService.class));
        int maxFullBatches = maxFullBatches();
        List<DocumentApproval> pending = approvals(
            IntStream.rangeClosed(1, maxFullBatches * 2 + 8).toArray());
        when(mapper.findPendingForWorkspace(7, 2)).thenReturn(pending.subList(0, 2));
        when(mapper.findPendingForWorkspaceAfter(anyInt(), anyInt(), anyInt()))
            .thenAnswer(invocation -> {
                int afterId = invocation.getArgument(1);
                return pending.stream()
                    .filter(approval -> approval.getId() > afterId)
                    .limit(2)
                    .toList();
            });
        Set<Integer> visited = new HashSet<>();

        for (int sweep = 0; sweep < maxFullBatches * 2 + 4; sweep++) {
            scheduler.selectPendingBatch(7, 2).stream()
                .map(DocumentApproval::getId)
                .forEach(visited::add);
        }

        assertTrue(visited.contains(maxFullBatches * 2 + 8));
        verify(mapper, times(2)).findPendingForWorkspace(7, 2);
    }

    @Test
    void workspaceBatchResolvesOnePostLockPoolForEveryTermination() throws Exception {
        DocumentApprovalMapper mapper = mock(DocumentApprovalMapper.class);
        DocumentApprovalService approvalService = mock(DocumentApprovalService.class);
        PlatformTransactionManager transactionManager = transactionManager();
        ApprovalReconciliationScheduler scheduler = scheduler(
            mapper, approvalService, transactionManager);
        setBatchSize(scheduler, 2);
        List<DocumentApproval> approvals = approvals(1, 2);
        when(mapper.findPendingForWorkspace(7, 2)).thenReturn(approvals);
        DocumentApprovalService.ApproverPool pool =
            new DocumentApprovalService.ApproverPool(List.of(), Set.of());
        when(approvalService.reconciliationApproverPool(7)).thenReturn(pool);

        ApprovalReconciliationScheduler.BatchResult result = scheduler.reconcileBatch(7);

        assertEquals(2, result.attemptedCount());
        assertEquals(0, result.failedCount());
        verify(approvalService, times(1)).reconciliationApproverPool(7);
        for (DocumentApproval approval : approvals) {
            verify(approvalService).reconcileApproval(7, approval, pool);
        }
        ArgumentCaptor<TransactionDefinition> definitions =
            ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(3)).getTransaction(definitions.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRED,
            definitions.getAllValues().getFirst().getPropagationBehavior());
        assertTrue(definitions.getAllValues().subList(1, 3).stream()
            .allMatch(definition -> definition.getPropagationBehavior()
                == TransactionDefinition.PROPAGATION_NESTED));
    }

    private static ApprovalReconciliationScheduler scheduler(
            DocumentApprovalMapper mapper, DocumentApprovalService approvalService) {
        return scheduler(mapper, approvalService, transactionManager());
    }

    private static ApprovalReconciliationScheduler scheduler(
            DocumentApprovalMapper mapper,
            DocumentApprovalService approvalService,
            PlatformTransactionManager transactionManager) {
        return new ApprovalReconciliationScheduler(
            mapper,
            mock(WorkspaceMapper.class),
            mock(PlacementRegistry.class),
            mock(TenantWorkScope.class),
            approvalService,
            mock(AutomationExecutor.class),
            mock(SystemActor.class),
            mock(JobRunRecorder.class),
            new TransactionTemplate(transactionManager));
    }

    private static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return transactionManager;
    }

    private static List<DocumentApproval> approvals(int... ids) {
        return java.util.Arrays.stream(ids).mapToObj(id -> {
            DocumentApproval approval = new DocumentApproval();
            approval.setId(id);
            return approval;
        }).toList();
    }

    private static List<Integer> ids(List<DocumentApproval> approvals) {
        return approvals.stream().map(DocumentApproval::getId).toList();
    }

    private static int maxFullBatches() throws ReflectiveOperationException {
        Field field = ApprovalReconciliationScheduler.class
            .getDeclaredField("MAX_CONSECUTIVE_FULL_BATCHES");
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static void setBatchSize(ApprovalReconciliationScheduler scheduler, int batchSize)
            throws ReflectiveOperationException {
        Field field = ApprovalReconciliationScheduler.class.getDeclaredField("batchSize");
        field.setAccessible(true);
        field.setInt(scheduler, batchSize);
    }
}
