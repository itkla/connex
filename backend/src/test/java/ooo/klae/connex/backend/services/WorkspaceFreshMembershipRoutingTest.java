package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.MemberDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import ooo.klae.connex.backend.services.ShareWorkspaceControlAccess.OrganizationWorkspaces;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.tenant.TenantWorkspaceScopeSnapshot;

/** Exercises public membership primitives with real routing and transaction boundaries, without SQL. */
class WorkspaceFreshMembershipRoutingTest {
    private final TenantContext context = new TenantContext();
    private final TenantCatalogResolver catalogs = mock(TenantCatalogResolver.class);
    private final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
    private final TenantLifecycleControlMapper lifecycle = mock(TenantLifecycleControlMapper.class);
    private final TenantWorkspaceScopeControlOperations preparation = mock(TenantWorkspaceScopeControlOperations.class);
    private final UserOffboardingService offboarding = mock(UserOffboardingService.class);
    private final NotificationStateVersionService versions = mock(NotificationStateVersionService.class);
    private final AuditService audit = mock(AuditService.class);
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
    private final TenantWorkScope scope = new TenantWorkScope(context, catalogs, workspaces, lifecycle, preparation);
    private final FreshMembershipTransaction membership = new FreshMembershipTransaction(scope, new TransactionTemplate(transactions));
    private final WorkspaceService service = new WorkspaceService(
        workspaces, mock(UserMapper.class), mock(OrganizationMapper.class), mock(AiAssistantPrivacyService.class),
        mock(OrgMemberService.class),
        mock(OrgAllowedDomainService.class), mock(RoleMapper.class), mock(NotificationMapper.class),
        offboarding, mock(WorkspaceMembershipRemovalTransaction.class), membership, scope,
        mock(NotificationDelivery.class), versions, context, audit, mock(SystemActor.class),
        mock(SessionSecurityService.class), mock(PrivilegedAccountService.class), mock(AccountDeletionReservationRead.class),
        mock(RegistrationVerificationService.class), mock(OwnershipOfferWriter.class), mock(PrivilegedMfaLockedAuthorization.class),
        mock(org.springframework.context.ApplicationEventPublisher.class));

    @AfterEach
    void clear() {
        context.clear();
        TransactionSynchronizationManager.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void routedMembershipPreparesADifferentTargetBeforeOpeningItsTransaction(boolean pending) throws Exception {
        prepareConnection();
        when(catalogs.isCatalogPerPlacement()).thenReturn(true);
        var outer = snapshot(3, 5, "cnx_previous");
        context.setWithWorkspaceScopes(3, 5, 11, "owner", "cnx_previous", outer);
        var target = snapshot(7, 13, "cnx_target");
        when(preparation.forWorkspace(7, false, null)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertNull(context.getCatalog());
            return target;
        });
        when(workspaces.getOrgId(7)).thenReturn(13);
        doAnswer(invocation -> scope.withWorkspacePlacement(7, (orgId, catalog) -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,
                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            assertSame(connection, DataSourceUtils.getConnection(dataSource));
            var caller = context.snapshot();
            context.set(7, orgId, 42, "member", catalog);
            try {
                assertSame(target, context.getWorkspaceScopes());
                var sharing = new ShareWorkspaceControlAccess(workspaces, scope, context, transactions);
                assertSame(target.requireSharing(), sharing.getForWorkspace(7));
            } finally {
                context.restore(caller);
            }
            return null;
        })).when(offboarding).prepareFreshMembership(7, 42);

        if (pending) {
            User actor = user(11);
            User targetUser = user(42);
            MemberDto member = new MemberDto();
            when(workspaces.getMember(7, 42)).thenReturn(member);
            assertSame(member, service.addPendingMember(7, actor, targetUser, "member"));
            verify(workspaces).addPendingMember(7, 42, "member");
        } else {
            service.ensureActiveMember(7, 42, "member");
            verify(workspaces).addMember(7, 42, "member");
        }

        assertOuterContext(outer);
        verify(preparation, times(1)).forWorkspace(7, false, null);
        verify(dataSource, times(1)).getConnection();
        verify(connection).commit();
        verify(versions).markChanged(42);
    }

    @Test
    void anUnpreparedTargetInsideAnExistingRoutedTransactionStillFailsBeforeAnyControlRead() throws Exception {
        prepareConnection();
        when(catalogs.isCatalogPerPlacement()).thenReturn(true);
        var outer = snapshot(3, 5, "cnx_previous");
        context.setWithWorkspaceScopes(3, 5, 11, "owner", "cnx_previous", outer);

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThrows(TenantScopeSnapshotMissingException.class, () -> service.ensureActiveMember(7, 42, "member"));
            assertOuterContext(outer);
        });

        verifyNoInteractions(preparation, workspaces, offboarding, versions, audit);
        verify(dataSource, times(1)).getConnection();
    }

    @Test
    void invitationAndSsoNestingReusesThePreparedTargetAndTransaction() throws Exception {
        prepareConnection();
        when(catalogs.isCatalogPerPlacement()).thenReturn(true);
        var target = snapshot(7, 13, "cnx_target");
        when(preparation.forWorkspace(7, false, null)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return target;
        });
        when(workspaces.getOrgId(7)).thenReturn(13);
        doAnswer(invocation -> scope.withWorkspacePlacement(7, (orgId, catalog) -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertSame(target, context.getWorkspaceScopes());
            assertSame(connection, DataSourceUtils.getConnection(dataSource));
            return null;
        })).when(offboarding).prepareFreshMembership(7, 42);

        assertEquals("joined", membership.execute(7, () -> {
            service.ensureActiveMember(7, 42, "member");
            return "joined";
        }));

        verify(preparation, times(1)).forWorkspace(7, false, null);
        verify(dataSource, times(1)).getConnection();
        verify(connection, times(1)).commit();
        assertNull(context.getWorkspaceScopes());
        assertNull(context.getCatalog());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void singleDatabaseKeepsItsLazyQueriesAndOwnerRefusal(boolean owner) throws Exception {
        prepareConnection();
        when(workspaces.getOrgId(7)).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertNull(context.getCatalog());
            return 13;
        });
        if (owner) {
            assertThrows(BadRequestException.class, () -> service.ensureActiveMember(7, 42, "owner"));
            verifyNoInteractions(workspaces, offboarding, versions, audit);
            verify(connection).rollback();
        } else {
            service.ensureActiveMember(7, 42, "member");
            verify(workspaces, times(1)).getOrgId(7);
            verify(workspaces, times(1)).isMember(7, 42);
            verify(offboarding).prepareFreshMembership(7, 42);
            verify(workspaces).addMember(7, 42, "member");
            verify(versions).markChanged(42);
            verify(connection).commit();
        }
        verifyNoInteractions(preparation, lifecycle);
        verify(catalogs, never()).resolveCatalog(13);
        assertNull(context.getWorkspaceScopes());
        verify(dataSource, times(1)).getConnection();
    }

    @Test
    void requestPreparationUsesTheMandatoryWorkspaceCollaboratorAndIsInertInSingleDatabase() {
        assertNull(service.prepareWorkspaceScopes(7, 13, null));
        verifyNoInteractions(preparation, workspaces, lifecycle, dataSource);
        when(catalogs.isCatalogPerPlacement()).thenReturn(true);
        var target = snapshot(7, 13, "cnx_target");
        when(preparation.load(7, 13, "cnx_target", null)).thenReturn(target);
        assertSame(target, service.prepareWorkspaceScopes(7, 13, "cnx_target"));
        verify(preparation).load(7, 13, "cnx_target", null);
    }

    @Test
    void singleDatabasePendingMembershipRetainsNullableResultAndLazyCleanup() throws Exception {
        prepareConnection();
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,
                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            assertNull(context.getWorkspaceScopes());
            return null;
        }).when(offboarding).prepareFreshMembership(7, 42);

        assertNull(service.addPendingMember(7, user(11), user(42), "member"));

        verify(offboarding).prepareFreshMembership(7, 42);
        verify(workspaces).addPendingMember(7, 42, "member");
        verify(workspaces).getMember(7, 42);
        verify(workspaces, never()).getOrgId(7);
        verifyNoInteractions(preparation, lifecycle);
        verify(dataSource, times(1)).getConnection();
        verify(connection).commit();
    }

    private void assertOuterContext(TenantWorkspaceScopeSnapshot outer) {
        assertTrue(context.isResolved());
        assertFalse(context.hasControlSelection());
        assertEquals(3, context.getWorkspaceId());
        assertEquals(5, context.getOrgId());
        assertEquals(11, context.getUserId());
        assertEquals("owner", context.getRole());
        assertEquals("cnx_previous", context.getScopeCatalog());
        assertEquals("cnx_previous", context.getCatalog());
        assertSame(outer, context.getWorkspaceScopes());
    }

    private void prepareConnection() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
    }

    private static User user(int id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private static TenantWorkspaceScopeSnapshot snapshot(int workspaceId, int orgId, String catalog) {
        return new TenantWorkspaceScopeSnapshot(workspaceId, orgId, catalog,
            Optional.of(new OrganizationWorkspaces(orgId, new LinkedHashMap<>(Map.of(workspaceId, "workspace")))),
            new WorkspaceScope(orgId, List.of(workspaceId), "[" + workspaceId + "]"));
    }
}
