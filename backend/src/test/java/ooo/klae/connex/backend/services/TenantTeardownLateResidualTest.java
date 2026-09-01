package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ooo.klae.connex.backend.dto.TenantStorageResidual;
import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.services.TenantLifecycleAccess.Route;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.AcquiredWorkspace;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.OperationLease;
import ooo.klae.connex.backend.storage.ObjectDeletionRetryQueue;
import ooo.klae.connex.backend.tenant.ControlWorkspaceLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.TableLifecycle;
import ooo.klae.connex.backend.tenant.TenantLifecycleProperties;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class TenantTeardownLateResidualTest {
    private static final int ORG_ID = 3;
    private static final int WORKSPACE_ID = 5;
    private static final int ACTOR_ID = 7;

    private final SessionSecurityService sessionSecurityService =
        mock(SessionSecurityService.class);
    private final TenantWorkScope tenantWorkScope = mock(TenantWorkScope.class);
    private final TenantLifecycleControlMapper controlMapper =
        mock(TenantLifecycleControlMapper.class);
    private final TenantLifecycleControlOperations controlOperations =
        mock(TenantLifecycleControlOperations.class);
    private final TenantLifecycleAccess lifecycleAccess =
        mock(TenantLifecycleAccess.class);
    private final TenantTeardownTenantTransaction tenantTransaction =
        mock(TenantTeardownTenantTransaction.class);
    private final ControlWorkspaceLifecycleTransaction controlWorkspaceTransaction =
        mock(ControlWorkspaceLifecycleTransaction.class);
    private final ObjectDeletionRetryQueue deletionRetryQueue =
        mock(ObjectDeletionRetryQueue.class);
    private final AuditService auditService = mock(AuditService.class);
    private final TenantLifecycleProperties properties = new TenantLifecycleProperties();
    private final Clock clock = Clock.systemUTC();

    private TenantTeardownService service;

    @BeforeEach
    void setUp() {
        properties.setTeardownSettleDelay(Duration.ZERO);
        service = new TenantTeardownService(
            sessionSecurityService,
            tenantWorkScope,
            controlMapper,
            controlOperations,
            lifecycleAccess,
            tenantTransaction,
            controlWorkspaceTransaction,
            deletionRetryQueue,
            auditService,
            properties,
            clock);
        when(tenantWorkScope.unrouted(any())).thenAnswer(invocation ->
            invocation.<Supplier<?>>getArgument(0).get());
        when(lifecycleAccess.withRoute(any(), anyInt(), any())).thenAnswer(invocation ->
            invocation.<Supplier<?>>getArgument(2).get());
        when(tenantTransaction.objectKeys(anyInt(), any(), anyInt()))
            .thenReturn(List.of());
        when(tenantTransaction.storageResidual(anyInt()))
            .thenReturn(new TenantStorageResidual(0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void lateControlWorkspaceResidualIsResweptAndVerifiedAfterRootDeletion() {
        WorkspaceLifecycleRef workspace = new WorkspaceLifecycleRef(
            WORKSPACE_ID, ORG_ID, "Workspace", "workspace", "active");
        OperationLease lease = new OperationLease(
            ORG_ID, WORKSPACE_ID, "teardown", "lease-token");
        Route route = new Route(ORG_ID, WORKSPACE_ID, null);
        ControlWorkspaceLifecycleRegistry.TableLifecycle clientError =
            ControlWorkspaceLifecycleRegistry.declarations().get("client_error");
        when(controlMapper.findWorkspaceOrCleanupInOrg(ORG_ID, WORKSPACE_ID))
            .thenReturn(workspace);
        when(controlOperations.acquireWorkspaceTeardown(ORG_ID, WORKSPACE_ID, ACTOR_ID))
            .thenReturn(new AcquiredWorkspace(workspace, lease));
        when(lifecycleAccess.capture(workspace, ORG_ID)).thenReturn(route);
        when(controlWorkspaceTransaction.count(WORKSPACE_ID, clientError))
            .thenReturn(0L, 1L, 0L);

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> service.teardownWorkspace(
                ORG_ID, WORKSPACE_ID, ACTOR_ID, "workspace"));

        assertTrue(exception.getMessage().contains("trusted cleanup clean=true"));
        verify(controlWorkspaceTransaction, atLeast(2)).deleteBatch(
            WORKSPACE_ID,
            clientError,
            properties.getTableBatchSize());
        verify(controlOperations).completeWorkspaceCleanup(
            ORG_ID, WORKSPACE_ID, ACTOR_ID, lease);
    }

    @Test
    void lateResidualIsResweptThroughCapturedRouteBeforeFailureReturns() {
        WorkspaceLifecycleRef workspace = new WorkspaceLifecycleRef(
            WORKSPACE_ID,
            ORG_ID,
            "Workspace",
            "workspace",
            "active");
        OperationLease lease = new OperationLease(
            ORG_ID,
            WORKSPACE_ID,
            "teardown",
            "lease-token");
        Route route = new Route(ORG_ID, WORKSPACE_ID, null);
        TableLifecycle lateTable = TenantLifecycleRegistry.require(
            "record_creation_template_version");
        when(controlMapper.findWorkspaceOrCleanupInOrg(ORG_ID, WORKSPACE_ID))
            .thenReturn(workspace);
        when(controlOperations.acquireWorkspaceTeardown(
                ORG_ID,
                WORKSPACE_ID,
                ACTOR_ID))
            .thenReturn(new AcquiredWorkspace(workspace, lease));
        when(lifecycleAccess.capture(workspace, ORG_ID)).thenReturn(route);
        when(tenantTransaction.count(WORKSPACE_ID, lateTable))
            .thenReturn(0L, 1L, 0L);

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> service.teardownWorkspace(
                ORG_ID,
                WORKSPACE_ID,
                ACTOR_ID,
                "workspace"));

        assertTrue(exception.getMessage().contains("trusted cleanup clean=true"));
        verify(tenantTransaction, atLeast(2)).deleteBatch(
            WORKSPACE_ID,
            lateTable,
            properties.getTableBatchSize());
        verify(auditService).recordFailureScoped(
            "tenant.workspace.teardown",
            "workspace",
            WORKSPACE_ID,
            null,
            ORG_ID,
            "workspace:" + WORKSPACE_ID,
            "Workspace teardown failed residual verification",
            "IllegalStateException");
        verify(controlOperations).completeWorkspaceCleanup(
            ORG_ID,
            WORKSPACE_ID,
            ACTOR_ID,
            lease);
    }

    @Test
    void successfulTeardownRepeatsFkOrderedContentPurgeAfterStorageDrain() {
        WorkspaceLifecycleRef workspace = new WorkspaceLifecycleRef(
            WORKSPACE_ID,
            ORG_ID,
            "Workspace",
            "workspace",
            "active");
        OperationLease lease = new OperationLease(
            ORG_ID,
            WORKSPACE_ID,
            "teardown",
            "lease-token");
        Route route = new Route(ORG_ID, WORKSPACE_ID, null);
        when(controlMapper.findWorkspaceOrCleanupInOrg(ORG_ID, WORKSPACE_ID))
            .thenReturn(workspace);
        when(controlOperations.acquireWorkspaceTeardown(
                ORG_ID,
                WORKSPACE_ID,
                ACTOR_ID))
            .thenReturn(new AcquiredWorkspace(workspace, lease));
        when(lifecycleAccess.capture(workspace, ORG_ID)).thenReturn(route);

        service.teardownWorkspace(
            ORG_ID,
            WORKSPACE_ID,
            ACTOR_ID,
            "workspace");

        TableLifecycle attempt = TenantLifecycleRegistry.require("workflow_step_attempt");
        TableLifecycle step = TenantLifecycleRegistry.require("workflow_step_run");
        TableLifecycle run = TenantLifecycleRegistry.require("workflow_run");
        TableLifecycle outbox = TenantLifecycleRegistry.require("workflow_trigger_outbox");
        TableLifecycle runtimeWorkspace =
            TenantLifecycleRegistry.require("workflow_runtime_workspace");
        TableLifecycle version = TenantLifecycleRegistry.require("workflow_version");
        TableLifecycle workflow = TenantLifecycleRegistry.require("workflow");
        int batchSize = properties.getTableBatchSize();
        verify(tenantTransaction, times(2)).deleteBatch(WORKSPACE_ID, attempt, batchSize);
        verify(tenantTransaction, times(2)).deleteBatch(WORKSPACE_ID, step, batchSize);
        verify(tenantTransaction, times(2)).deleteBatch(WORKSPACE_ID, run, batchSize);
        verify(tenantTransaction, times(2)).deleteBatch(WORKSPACE_ID, outbox, batchSize);
        verify(tenantTransaction, times(2)).deleteBatch(
            WORKSPACE_ID, runtimeWorkspace, batchSize);
        verify(tenantTransaction, times(2)).deleteBatch(WORKSPACE_ID, version, batchSize);
        verify(tenantTransaction, times(2)).deleteBatch(WORKSPACE_ID, workflow, batchSize);
        InOrder order = inOrder(tenantTransaction);
        order.verify(tenantTransaction).deleteBatch(WORKSPACE_ID, attempt, batchSize);
        order.verify(tenantTransaction).deleteBatch(WORKSPACE_ID, step, batchSize);
        order.verify(tenantTransaction).deleteBatch(WORKSPACE_ID, run, batchSize);
        order.verify(tenantTransaction).deleteBatch(WORKSPACE_ID, outbox, batchSize);
        order.verify(tenantTransaction).deleteBatch(
            WORKSPACE_ID, runtimeWorkspace, batchSize);
        order.verify(tenantTransaction).deleteBatch(WORKSPACE_ID, version, batchSize);
        order.verify(tenantTransaction).deleteBatch(WORKSPACE_ID, workflow, batchSize);
        order.verify(tenantTransaction).deleteBatch(WORKSPACE_ID, attempt, batchSize);
        order.verify(tenantTransaction).deleteBatch(WORKSPACE_ID, step, batchSize);
        order.verify(tenantTransaction).deleteBatch(WORKSPACE_ID, run, batchSize);
        order.verify(tenantTransaction).deleteBatch(WORKSPACE_ID, outbox, batchSize);
        order.verify(tenantTransaction).deleteBatch(
            WORKSPACE_ID, runtimeWorkspace, batchSize);
        order.verify(tenantTransaction).deleteBatch(WORKSPACE_ID, version, batchSize);
        order.verify(tenantTransaction).deleteBatch(WORKSPACE_ID, workflow, batchSize);
    }
}
