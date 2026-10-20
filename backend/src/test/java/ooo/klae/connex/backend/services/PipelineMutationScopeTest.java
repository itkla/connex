package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealStageHistory;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.DealStageHistoryMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.support.TestTransactionManager;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Real proxies, scope boundaries and history recording across independent mutation commits. */
@ExtendWith(MockitoExtension.class)
class PipelineMutationScopeTest {
    @Mock private DealMapper dealMapper;
    @Mock private PipelineMapper pipelineMapper;
    @Mock private WorkspaceMapper controlMapper;
    @Mock private DealStageHistoryMapper historyMapper;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuthService authService;
    @Mock private AuditService auditService;
    @Mock private ReferenceService referenceService;
    @Mock private DealOutcomeWriter dealOutcomeWriter;
    @Mock private DuplicateDecisionLockService duplicateDecisionLockService;
    @Mock private RuleTriggerPublisher ruleTriggers;
    @Mock private NotificationChangePublisher notificationChanges;
    @InjectMocks private DealService target;

    private final TenantContext tenant = new TenantContext();
    private final TestTransactionManager transactions = new TestTransactionManager();
    private final Stage stage = new Stage();
    private RecordVisibilityScopeControlAccess scopes;
    private DealService deals;

    @BeforeEach
    void setUp() {
        tenant.set(7, 90, 11, "owner", null);
        TenantWorkScope work = new TenantWorkScope(tenant, mock(TenantCatalogResolver.class), controlMapper);
        scopes = spy(new RecordVisibilityScopeControlAccess(
            new ShareWorkspaceControlAccess(controlMapper, work, tenant, transactions), tenant));
        ReflectionTestUtils.setField(target, "recordVisibilityScopeControlAccess", scopes);
        ReflectionTestUtils.setField(target, "dealStageHistoryService", new DealStageHistoryService(
            historyMapper, pipelineMapper, workspaceService, Clock.systemUTC()));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        deals = (DealService) proxy.getProxy();
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        Workspace viewer = new Workspace();
        viewer.setId(7);
        viewer.setOrgId(90);
        viewer.setName("Viewer");
        when(controlMapper.findOrganizationWorkspacesForShare(7)).thenAnswer(invocation -> {
            return List.of(viewer);
        });
        Pipeline pipeline = new Pipeline();
        pipeline.setId(12);
        stage.setId(13);
        stage.setPipeline(pipeline);
        stage.setName("Target");
        when(pipelineMapper.getVisibleStageById(7, "[7]", 13)).thenReturn(stage);
        when(referenceService.hydrateDeals(eq(7), anyList())).thenAnswer(invocation -> invocation.getArgument(1));
    }

    @AfterEach
    void clearContext() {
        tenant.clear();
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
    }

    @Test
    void aThousandDealMutationsUseOneControlReadAndAThousandIndependentCommits() {
        List<Integer> ids = IntStream.rangeClosed(1, 1000).boxed().toList();
        List<TestTransactionManager.TestTransaction> identities = new ArrayList<>();
        List<Integer> completions = new ArrayList<>();
        when(dealMapper.existsOwned(eq(7), anyInt())).thenReturn(true);
        when(dealMapper.getDealById(eq(7), anyInt())).thenAnswer(invocation -> deal(invocation.getArgument(1)));
        when(dealMapper.getDealByIdForUpdate(eq(7), anyInt())).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            identities.add(transactions.currentTransaction());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    completions.add(status);
                }
            });
            return deal(invocation.getArgument(1));
        });
        BulkOperationService bulk = new BulkOperationService(workspaceService,
            mock(PersonService.class), mock(CompanyService.class), deals, mock(PersonMapper.class),
            mock(CompanyMapper.class), dealMapper, mock(TagMapper.class), pipelineMapper, scopes);

        var result = bulk.changeStageForDeals(ids, 13);

        assertEquals(1000, result.getSucceeded());
        assertEquals(0, result.getFailed());
        assertEquals(1000, transactions.beginCount());
        assertEquals(1000, identities.stream().distinct().count());
        assertEquals(1000, completions.size());
        assertTrue(completions.stream().allMatch(status -> status == TransactionSynchronization.STATUS_COMMITTED));
        verify(controlMapper, times(1)).findOrganizationWorkspacesForShare(7);
        verify(scopes, times(1)).getForWorkspace(7);
        verify(historyMapper, times(1000)).insert(any(DealStageHistory.class));
    }

    @Test
    void anInvalidRowRollsBackAloneAndTheNextCommitStillUsesTheBatchScope() {
        List<Integer> completions = new ArrayList<>();
        when(dealMapper.existsOwned(eq(7), anyInt())).thenReturn(true);
        when(dealMapper.getDealById(eq(7), anyInt())).thenAnswer(invocation -> deal(invocation.getArgument(1)));
        when(dealMapper.getDealByIdForUpdate(eq(7), anyInt())).thenAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    completions.add(status);
                }
            });
            int id = invocation.getArgument(1);
            Deal locked = deal(id);
            if (id == 2) {
                locked.setPipelineId(99);
            }
            return locked;
        });
        BulkOperationService bulk = new BulkOperationService(workspaceService,
            mock(PersonService.class), mock(CompanyService.class), deals, mock(PersonMapper.class),
            mock(CompanyMapper.class), dealMapper, mock(TagMapper.class), pipelineMapper, scopes);

        var result = bulk.changeStageForDeals(List.of(1, 2, 3), 13);

        assertEquals(2, result.getSucceeded());
        assertEquals(1, result.getFailed());
        assertEquals(3, transactions.beginCount());
        assertEquals(List.of(TransactionSynchronization.STATUS_COMMITTED,
            TransactionSynchronization.STATUS_ROLLED_BACK, TransactionSynchronization.STATUS_COMMITTED), completions);
        verify(controlMapper, times(1)).findOrganizationWorkspacesForShare(7);
        verify(scopes, times(1)).getForWorkspace(7);
        verify(historyMapper, times(2)).insert(any(DealStageHistory.class));
    }

    @Test
    void creationAndItsHistoryShareTheScopeResolvedBeforeAuthorizationAndTheMutex() {
        User actor = new User();
        actor.setId(11);
        when(workspaceService.getCurrentUserId()).thenReturn(11);
        when(authService.getCurrentUser()).thenReturn(actor);
        when(pipelineMapper.pipelineExists(7, "[7]", 12)).thenReturn(true);
        Deal created = deal(41);
        created.setStageId(13);
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return 1;
        }).when(historyMapper).insert(any(DealStageHistory.class));

        deals.create(created);

        var order = inOrder(controlMapper, workspaceService, duplicateDecisionLockService,
            dealOutcomeWriter, pipelineMapper, historyMapper);
        order.verify(controlMapper).findOrganizationWorkspacesForShare(7);
        order.verify(workspaceService).lockAndRequirePermissions(eq(7), any());
        order.verify(duplicateDecisionLockService).lockCurrentOrganization();
        order.verify(dealOutcomeWriter).create(eq(created), any(), any());
        order.verify(pipelineMapper).getVisibleStageById(7, "[7]", 13);
        order.verify(historyMapper).insert(any(DealStageHistory.class));
        verify(controlMapper, times(1)).findOrganizationWorkspacesForShare(7);
        verify(scopes, times(1)).getForWorkspace(7);
    }

    private static Deal deal(int id) {
        Deal deal = new Deal();
        deal.setId(id);
        deal.setWorkspaceId(7);
        deal.setName("Deal " + id);
        deal.setPipelineId(12);
        deal.setStageId(14);
        deal.setValue(BigDecimal.TEN);
        return deal;
    }
}
