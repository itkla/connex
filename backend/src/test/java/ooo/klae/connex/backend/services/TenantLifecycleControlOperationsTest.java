package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.OperationLease;
import ooo.klae.connex.backend.tenant.TenantLifecycleProperties;

/**
 * Pins the control-plane admission rules that no in-memory counter may duplicate:
 * concurrent exports are capped by the lease table itself, an open APPI request
 * refuses teardown, and terminal root deletion clears the retained subject link
 * before the workspace row disappears.
 */
@ExtendWith(MockitoExtension.class)
class TenantLifecycleControlOperationsTest {
    private static final int ORG_ID = 7;
    private static final int WORKSPACE_ID = 9;
    private static final int ACTOR_ID = 3;
    private static final WorkspaceLifecycleRef WORKSPACE =
        new WorkspaceLifecycleRef(WORKSPACE_ID, ORG_ID, "Lifecycle", "lifecycle", "active");

    @Mock private TenantLifecycleControlMapper mapper;

    private TenantLifecycleProperties properties;
    private TenantLifecycleControlOperations operations;

    @BeforeEach
    void setUp() {
        properties = new TenantLifecycleProperties();
        operations = new TenantLifecycleControlOperations(mapper, properties);
    }

    @Test
    void exportIsRefusedWithoutALeaseRowOnceTheConcurrentCapIsReached() {
        properties.setMaxConcurrentExports(2);
        when(mapper.lockActiveWorkspaceForExport(ORG_ID, WORKSPACE_ID)).thenReturn(WORKSPACE);
        when(mapper.isOrgAdminForLifecycle(ORG_ID, ACTOR_ID)).thenReturn(true);
        when(mapper.countOperationLeasesOfKind("export")).thenReturn(2);

        assertThrows(
            TooManyRequestsException.class,
            () -> operations.acquireExport(ORG_ID, WORKSPACE_ID, ACTOR_ID));

        verify(mapper, never()).insertOperationLease(anyInt(), anyInt(), anyString(), anyString());
    }

    @Test
    void exportBelowTheConcurrentCapAcquiresItsLease() {
        properties.setMaxConcurrentExports(2);
        when(mapper.lockActiveWorkspaceForExport(ORG_ID, WORKSPACE_ID)).thenReturn(WORKSPACE);
        when(mapper.isOrgAdminForLifecycle(ORG_ID, ACTOR_ID)).thenReturn(true);
        when(mapper.countOperationLeasesOfKind("export")).thenReturn(1);

        operations.acquireExport(ORG_ID, WORKSPACE_ID, ACTOR_ID);

        verify(mapper).insertOperationLease(
            eq(ORG_ID),
            eq(WORKSPACE_ID),
            eq("export"),
            anyString());
    }

    @Test
    void teardownLeasesIgnoreTheConcurrentExportCap() {
        properties.setMaxConcurrentExports(1);
        when(mapper.lockWorkspaceInOrg(ORG_ID, WORKSPACE_ID)).thenReturn(WORKSPACE);
        when(mapper.isOrgOwnerForLifecycle(ORG_ID, ACTOR_ID)).thenReturn(true);

        operations.acquireWorkspaceTeardown(ORG_ID, WORKSPACE_ID, ACTOR_ID);

        verify(mapper, never()).countOperationLeasesOfKind(anyString());
        verify(mapper).insertOperationLease(
            eq(ORG_ID),
            eq(WORKSPACE_ID),
            eq("teardown"),
            anyString());
    }

    @Test
    void openSubjectRequestRefusesWorkspaceTeardown() {
        when(mapper.countOpenSubjectRequestsForWorkspace(ORG_ID, WORKSPACE_ID)).thenReturn(1);

        assertThrows(
            ConflictException.class,
            () -> operations.requireNoOpenSubjectRequestsForWorkspace(ORG_ID, WORKSPACE_ID));
    }

    @Test
    void openSubjectRequestRefusesOrganizationTeardown() {
        when(mapper.countOpenSubjectRequestsForOrg(ORG_ID)).thenReturn(1);

        assertThrows(
            ConflictException.class,
            () -> operations.requireNoOpenSubjectRequestsForOrganization(ORG_ID));
    }

    @Test
    void terminalRootDeletionClearsRetainedSubjectLinksBeforeDeletingTheWorkspace() {
        OperationLease lease = new OperationLease(ORG_ID, WORKSPACE_ID, "teardown", "token");
        when(mapper.lockWorkspaceInOrg(ORG_ID, WORKSPACE_ID)).thenReturn(WORKSPACE);
        when(mapper.isOrgOwnerForLifecycle(ORG_ID, ACTOR_ID)).thenReturn(true);
        when(mapper.ownsOperationLease(WORKSPACE_ID, "teardown", "token")).thenReturn(true);
        when(mapper.deleteWorkspace(ORG_ID, WORKSPACE_ID)).thenReturn(1);

        operations.deleteWorkspaceRoot(ORG_ID, WORKSPACE_ID, ACTOR_ID, lease);

        InOrder order = inOrder(mapper);
        order.verify(mapper).clearSubjectRequestWorkspaceLinks(ORG_ID, WORKSPACE_ID);
        order.verify(mapper).deleteWorkspace(ORG_ID, WORKSPACE_ID);
    }
}
