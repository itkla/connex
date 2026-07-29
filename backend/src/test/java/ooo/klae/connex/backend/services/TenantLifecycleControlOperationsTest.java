package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;

import ooo.klae.connex.backend.beans.FederatedIdentity;
import ooo.klae.connex.backend.dto.OrganizationLifecycleRef;
import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.OperationLease;

/**
 * Pins fail-closed persisted leases, APPI teardown refusal, and terminal link cleanup.
 */
@ExtendWith(MockitoExtension.class)
class TenantLifecycleControlOperationsTest {
    private static final int ORG_ID = 7;
    private static final int WORKSPACE_ID = 9;
    private static final int ACTOR_ID = 3;
    private static final WorkspaceLifecycleRef WORKSPACE =
        new WorkspaceLifecycleRef(WORKSPACE_ID, ORG_ID, "Lifecycle", "lifecycle", "active");
    private static final OrganizationLifecycleRef ORGANIZATION =
        new OrganizationLifecycleRef(ORG_ID, "Lifecycle Org", "lifecycle-org", "active");

    @Mock private TenantLifecycleControlMapper mapper;
    @Mock private UserMapper userMapper;

    private TenantLifecycleControlOperations operations;

    @BeforeEach
    void setUp() {
        operations = new TenantLifecycleControlOperations(mapper, userMapper);
        lenient().when(userMapper.lockByIdForShare(ACTOR_ID)).thenReturn(ACTOR_ID);
    }

    @Test
    void exportLocksExactRootsAndGlobalAdmissionBeforePersistingItsLease() {
        when(mapper.lockActiveOrganizationForShare(ORG_ID)).thenReturn(ORG_ID);
        when(mapper.lockWorkspaceForShare(WORKSPACE_ID))
            .thenReturn(WORKSPACE);
        when(mapper.lockOrgAdminMembershipForUpdate(ORG_ID, ACTOR_ID)).thenReturn(ACTOR_ID);
        when(mapper.lockExportAdmissionCapacityNowait()).thenReturn(4);

        operations.acquireExport(ORG_ID, WORKSPACE_ID, ACTOR_ID);

        InOrder order = inOrder(userMapper, mapper);
        order.verify(userMapper).lockByIdForShare(ACTOR_ID);
        order.verify(mapper).lockWorkspaceForShare(WORKSPACE_ID);
        order.verify(mapper).lockActiveOrganizationForShare(ORG_ID);
        order.verify(mapper).lockOrgAdminMembershipForUpdate(ORG_ID, ACTOR_ID);
        order.verify(mapper).lockExportAdmissionCapacityNowait();
        order.verify(mapper).countGlobalExportLeases();
        order.verify(mapper).insertOperationLease(
            eq(ORG_ID),
            eq(WORKSPACE_ID),
            eq("export"),
            anyString());
    }

    @Test
    void exportCapacityRejectsImmediatelyBeforeTokenInsertion() {
        stubExportLocks();
        when(mapper.lockExportAdmissionCapacityNowait()).thenReturn(4);
        when(mapper.countGlobalExportLeases()).thenReturn(4);

        assertThrows(
            TooManyRequestsException.class,
            () -> operations.acquireExport(ORG_ID, WORKSPACE_ID, ACTOR_ID));

        verify(mapper, never()).insertOperationLease(anyInt(), anyInt(), anyString(), anyString());
    }

    @Test
    void invalidPersistedExportCapacityFailsClosedBeforeCountingOrInsertion() {
        stubExportLocks();
        when(mapper.lockExportAdmissionCapacityNowait()).thenReturn(0, 5);

        assertThrows(
            IllegalStateException.class,
            () -> operations.acquireExport(ORG_ID, WORKSPACE_ID, ACTOR_ID));
        assertThrows(
            IllegalStateException.class,
            () -> operations.acquireExport(ORG_ID, WORKSPACE_ID, ACTOR_ID));

        verify(mapper, never()).countGlobalExportLeases();
        verify(mapper, never()).insertOperationLease(anyInt(), anyInt(), anyString(), anyString());
    }

    @Test
    void onlyTheDocumentedMySqlNowaitErrorMapsToTooManyRequests() {
        stubExportLocks();
        SQLException nowait = new SQLException("busy", "HY000", 3572);
        when(mapper.lockExportAdmissionCapacityNowait())
            .thenThrow(new CannotAcquireLockException("busy", nowait));

        assertThrows(
            TooManyRequestsException.class,
            () -> operations.acquireExport(ORG_ID, WORKSPACE_ID, ACTOR_ID));

        SQLException other = new SQLException("deadlock", "40001", 1213);
        CannotAcquireLockException failure =
            new CannotAcquireLockException("deadlock", other);
        doThrow(failure).when(mapper).lockExportAdmissionCapacityNowait();

        assertSame(
            failure,
            assertThrows(
                CannotAcquireLockException.class,
                () -> operations.acquireExport(ORG_ID, WORKSPACE_ID, ACTOR_ID)));
    }

    @Test
    void teardownLocksActorWorkspaceAndOrganizationBeforePersistingItsLease() {
        when(mapper.lockOrganization(ORG_ID)).thenReturn(ORGANIZATION);
        when(mapper.lockWorkspaceInOrg(WORKSPACE_ID)).thenReturn(WORKSPACE);
        when(mapper.isOrgOwnerForLifecycle(ORG_ID, ACTOR_ID)).thenReturn(true);

        operations.acquireWorkspaceTeardown(ORG_ID, WORKSPACE_ID, ACTOR_ID);

        InOrder order = inOrder(userMapper, mapper);
        order.verify(userMapper).lockByIdForShare(ACTOR_ID);
        order.verify(mapper).lockWorkspaceInOrg(WORKSPACE_ID);
        order.verify(mapper).lockOrganization(ORG_ID);
        order.verify(mapper).isOrgOwnerForLifecycle(ORG_ID, ACTOR_ID);
        order.verify(mapper).countOpenSubjectRequestsForWorkspace(ORG_ID, WORKSPACE_ID);
        verify(mapper).insertOperationLease(
            eq(ORG_ID),
            eq(WORKSPACE_ID),
            eq("teardown"),
            anyString());
    }

    @Test
    void teardownRejectsAnyDurableWorkspaceLeaseWithoutPollingOrInsertion() {
        when(mapper.lockOrganization(ORG_ID)).thenReturn(ORGANIZATION);
        when(mapper.lockWorkspaceInOrg(WORKSPACE_ID)).thenReturn(WORKSPACE);
        when(mapper.isOrgOwnerForLifecycle(ORG_ID, ACTOR_ID)).thenReturn(true);
        when(mapper.countAllOperationLeases(WORKSPACE_ID)).thenReturn(1);

        assertThrows(
            ConflictException.class,
            () -> operations.acquireWorkspaceTeardown(ORG_ID, WORKSPACE_ID, ACTOR_ID));

        verify(mapper, never()).insertOperationLease(anyInt(), anyInt(), anyString(), anyString());
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
    void aSubjectRequestOpenedAfterTheUnlockedCheckStillRefusesTheWorkspaceFence() {
        when(mapper.lockOrganization(ORG_ID)).thenReturn(ORGANIZATION);
        when(mapper.lockWorkspaceInOrg(WORKSPACE_ID)).thenReturn(WORKSPACE);
        when(mapper.isOrgOwnerForLifecycle(ORG_ID, ACTOR_ID)).thenReturn(true);
        when(mapper.countOpenSubjectRequestsForWorkspace(ORG_ID, WORKSPACE_ID)).thenReturn(1);

        assertThrows(
            ConflictException.class,
            () -> operations.acquireWorkspaceTeardown(ORG_ID, WORKSPACE_ID, ACTOR_ID));

        InOrder order = inOrder(userMapper, mapper);
        order.verify(userMapper).lockByIdForShare(ACTOR_ID);
        order.verify(mapper).lockWorkspaceInOrg(WORKSPACE_ID);
        order.verify(mapper).lockOrganization(ORG_ID);
        order.verify(mapper).countOpenSubjectRequestsForWorkspace(ORG_ID, WORKSPACE_ID);
        verify(mapper, never()).insertOperationLease(anyInt(), anyInt(), anyString(), anyString());
        verify(mapper, never()).markWorkspaceTearingDown(anyInt(), anyInt());
    }

    @Test
    void aSubjectRequestOpenedAfterTheUnlockedCheckStillRefusesTheOrganizationFence() {
        when(mapper.lockOrganization(ORG_ID)).thenReturn(ORGANIZATION);
        when(mapper.isOrgOwnerForLifecycle(ORG_ID, ACTOR_ID)).thenReturn(true);
        when(mapper.countOpenSubjectRequestsForOrg(ORG_ID)).thenReturn(1);

        assertThrows(
            ConflictException.class,
            () -> operations.markOrganizationTearingDown(ORG_ID, ACTOR_ID));

        InOrder order = inOrder(userMapper, mapper);
        order.verify(userMapper).lockByIdForShare(ACTOR_ID);
        order.verify(mapper).lockOrganization(ORG_ID);
        order.verify(mapper).countOpenSubjectRequestsForOrg(ORG_ID);
        verify(mapper, never()).markOrganizationTearingDown(anyInt());
    }

    @Test
    void terminalRootDeletionClearsRetainedSubjectLinksBeforeDeletingTheWorkspace() {
        OperationLease lease = new OperationLease(ORG_ID, WORKSPACE_ID, "teardown", "token");
        when(mapper.lockOrganization(ORG_ID)).thenReturn(ORGANIZATION);
        when(mapper.lockWorkspaceInOrg(WORKSPACE_ID)).thenReturn(WORKSPACE);
        when(mapper.isOrgOwnerForLifecycle(ORG_ID, ACTOR_ID)).thenReturn(true);
        when(mapper.ownsOperationLease(WORKSPACE_ID, "teardown", "token")).thenReturn(true);
        when(mapper.deleteWorkspace(ORG_ID, WORKSPACE_ID)).thenReturn(1);

        operations.deleteWorkspaceRoot(ORG_ID, WORKSPACE_ID, ACTOR_ID, lease);

        InOrder order = inOrder(mapper);
        order.verify(mapper).clearSubjectRequestWorkspaceLinks(ORG_ID, WORKSPACE_ID);
        order.verify(mapper).deleteWorkspace(ORG_ID, WORKSPACE_ID);
    }

    @Test
    void tombstoneOnlyRetryStillClearsRetainedSubjectLinks() {
        OperationLease lease = new OperationLease(ORG_ID, WORKSPACE_ID, "teardown", "token");
        WorkspaceLifecycleRef tombstone =
            new WorkspaceLifecycleRef(WORKSPACE_ID, ORG_ID, "Lifecycle", "lifecycle", "tearing_down");
        when(mapper.lockOrganization(ORG_ID)).thenReturn(ORGANIZATION);
        when(mapper.lockWorkspaceInOrg(WORKSPACE_ID)).thenReturn(null);
        when(mapper.lockCleanupTombstoneInOrg(WORKSPACE_ID)).thenReturn(tombstone);
        when(mapper.isOrgOwnerForLifecycle(ORG_ID, ACTOR_ID)).thenReturn(true);
        when(mapper.ownsOperationLease(WORKSPACE_ID, "teardown", "token")).thenReturn(true);

        operations.deleteWorkspaceRoot(ORG_ID, WORKSPACE_ID, ACTOR_ID, lease);

        verify(mapper).clearSubjectRequestWorkspaceLinks(ORG_ID, WORKSPACE_ID);
        verify(mapper, never()).deleteWorkspace(ORG_ID, WORKSPACE_ID);
    }

    @Test
    void cleanupCompletionLocksActorTombstoneOrganizationMemberAndLeaseInOrder() {
        OperationLease lease = new OperationLease(ORG_ID, WORKSPACE_ID, "teardown", "token");
        WorkspaceLifecycleRef tombstone =
            new WorkspaceLifecycleRef(WORKSPACE_ID, ORG_ID, "Lifecycle", "lifecycle", "tearing_down");
        when(mapper.lockCleanupTombstoneInOrg(WORKSPACE_ID)).thenReturn(tombstone);
        when(mapper.lockOrganization(ORG_ID)).thenReturn(ORGANIZATION);
        when(mapper.isOrgOwnerForLifecycle(ORG_ID, ACTOR_ID)).thenReturn(true);
        when(mapper.ownsOperationLease(WORKSPACE_ID, "teardown", "token")).thenReturn(true);
        when(mapper.deleteCleanupTombstone(ORG_ID, WORKSPACE_ID)).thenReturn(1);
        when(mapper.deleteOperationLease(WORKSPACE_ID, "teardown", "token")).thenReturn(1);

        operations.completeWorkspaceCleanup(ORG_ID, WORKSPACE_ID, ACTOR_ID, lease);

        InOrder order = inOrder(userMapper, mapper);
        order.verify(userMapper).lockByIdForShare(ACTOR_ID);
        order.verify(mapper).lockCleanupTombstoneInOrg(WORKSPACE_ID);
        order.verify(mapper).lockOrganization(ORG_ID);
        order.verify(mapper).isOrgOwnerForLifecycle(ORG_ID, ACTOR_ID);
        order.verify(mapper).ownsOperationLease(WORKSPACE_ID, "teardown", "token");
        order.verify(mapper).deleteCleanupTombstone(ORG_ID, WORKSPACE_ID);
        order.verify(mapper).deleteOperationLease(WORKSPACE_ID, "teardown", "token");
    }

    @Test
    void federatedIdentityCleanupLocksEveryUserThenOrganizationAndOwnerBeforeDeleting() {
        OrganizationLifecycleRef tearingDown =
            new OrganizationLifecycleRef(
                ORG_ID,
                "Lifecycle Org",
                "lifecycle-org",
                "tearing_down");
        List<FederatedIdentity> batch = List.of(
            identity(20, 8),
            identity(21, 2),
            identity(22, 8));
        when(mapper.findFederatedIdentityBatch(ORG_ID, 10))
            .thenReturn(batch, batch);
        when(userMapper.lockByIdForShare(2)).thenReturn(2);
        when(userMapper.lockByIdForShare(8)).thenReturn(8);
        when(mapper.lockOrganization(ORG_ID)).thenReturn(tearingDown);
        when(mapper.isOrgOwnerForLifecycle(ORG_ID, ACTOR_ID)).thenReturn(true);
        when(mapper.deleteFederatedIdentityBatch(ORG_ID, List.of(20, 21, 22)))
            .thenReturn(3);

        assertEquals(3, operations.deleteFederatedIdentityBatch(ORG_ID, ACTOR_ID, 10));

        InOrder order = inOrder(userMapper, mapper);
        order.verify(mapper).findFederatedIdentityBatch(ORG_ID, 10);
        order.verify(userMapper).lockByIdForShare(2);
        order.verify(userMapper).lockByIdForShare(ACTOR_ID);
        order.verify(userMapper).lockByIdForShare(8);
        order.verify(mapper).lockOrganization(ORG_ID);
        order.verify(mapper).isOrgOwnerForLifecycle(ORG_ID, ACTOR_ID);
        order.verify(mapper).findFederatedIdentityBatch(ORG_ID, 10);
        order.verify(mapper).deleteFederatedIdentityBatch(
            ORG_ID,
            List.of(20, 21, 22));
    }

    @Test
    void federatedIdentityCleanupRefusesAChangedPageBeforeDeleting() {
        OrganizationLifecycleRef tearingDown =
            new OrganizationLifecycleRef(
                ORG_ID,
                "Lifecycle Org",
                "lifecycle-org",
                "tearing_down");
        when(mapper.findFederatedIdentityBatch(ORG_ID, 10))
            .thenReturn(List.of(identity(20, 2)), List.of());
        when(userMapper.lockByIdForShare(2)).thenReturn(2);
        when(mapper.lockOrganization(ORG_ID)).thenReturn(tearingDown);
        when(mapper.isOrgOwnerForLifecycle(ORG_ID, ACTOR_ID)).thenReturn(true);

        assertThrows(
            ConflictException.class,
            () -> operations.deleteFederatedIdentityBatch(ORG_ID, ACTOR_ID, 10));

        verify(mapper, never()).deleteFederatedIdentityBatch(
            eq(ORG_ID),
            org.mockito.ArgumentMatchers.<Integer>anyList());
    }

    @Test
    void federatedIdentityCleanupRefusesAMissingDiscoveredUserBeforeDeleting() {
        when(mapper.findFederatedIdentityBatch(ORG_ID, 10))
            .thenReturn(List.of(identity(20, 2)));
        when(userMapper.lockByIdForShare(2)).thenReturn(null);

        assertThrows(
            ConflictException.class,
            () -> operations.deleteFederatedIdentityBatch(ORG_ID, ACTOR_ID, 10));

        verify(mapper, never()).lockOrganization(ORG_ID);
        verify(mapper, never()).deleteFederatedIdentityBatch(
            eq(ORG_ID),
            org.mockito.ArgumentMatchers.<Integer>anyList());
    }

    @Test
    void organizationRootDeletionRefusesEveryPersistedOperationLease() {
        when(mapper.lockOrganization(ORG_ID)).thenReturn(ORGANIZATION);
        when(mapper.isOrgOwnerForLifecycle(ORG_ID, ACTOR_ID)).thenReturn(true);
        when(mapper.countOperationLeasesInOrg(ORG_ID)).thenReturn(1);

        assertThrows(
            IllegalStateException.class,
            () -> operations.deleteOrganizationRoot(ORG_ID, ACTOR_ID));

        verify(mapper, never()).deleteOrganization(ORG_ID);
    }

    private void stubExportLocks() {
        when(mapper.lockActiveOrganizationForShare(ORG_ID)).thenReturn(ORG_ID);
        when(mapper.lockWorkspaceForShare(WORKSPACE_ID))
            .thenReturn(WORKSPACE);
        when(mapper.lockOrgAdminMembershipForUpdate(ORG_ID, ACTOR_ID)).thenReturn(ACTOR_ID);
    }

    private static FederatedIdentity identity(int id, int userId) {
        FederatedIdentity identity = new FederatedIdentity();
        identity.setId(id);
        identity.setUserId(userId);
        return identity;
    }
}
