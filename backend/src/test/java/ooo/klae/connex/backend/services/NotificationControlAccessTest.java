package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.NotificationMembership;
import ooo.klae.connex.backend.beans.NotificationMembershipAuthority;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;
import ooo.klae.connex.backend.mappers.NotificationControlMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.support.NotificationControlAccessFixture;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.ObjectMapper;

/** Exercises real read callbacks and transaction-bound authority without a database. */
@ExtendWith(MockitoExtension.class)
class NotificationControlAccessTest {
    @Mock private NotificationControlMapper mapper;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private TenantCatalogResolver resolver;

    private final TenantContext tenantContext = new TenantContext();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private NotificationControlAccessFixture fixture;
    private Connection connection;
    private PlatformTransactionManager transactions;
    private NotificationControlAccess access;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new NotificationControlAccessFixture();
        connection = fixture.connection();
        transactions = fixture.transactionManager();
        TenantWorkScope workScope = new TenantWorkScope(tenantContext, resolver, workspaceMapper,
            mock(ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper.class),
            new TenantWorkspaceScopeControlOperations(workspaceMapper, resolver));
        access = new NotificationControlAccess(mapper, workScope, tenantContext, objectMapper, transactions);
    }

    @AfterEach
    void clearContext() {
        tenantContext.clear();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void readCallbackAndBothBatchesRetainOneConnectionWithoutSuspension() throws Exception {
        NotificationMembership member = member(7, 900, "active", "active", "active");
        when(mapper.findRecipientMemberships(42)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertSame(connection, fixture.boundConnection());
            assertNull(tenantContext.getCatalog());
            return List.of(member);
        });
        when(mapper.findOrganizationWorkspaces(List.of(900))).thenAnswer(call -> {
            assertSame(connection, fixture.boundConnection());
            return List.of(workspace(7, 900), workspace(8, 900));
        });
        try (AnnotationConfigApplicationContext context = proxiedContext()) {
            NotificationControlAccess proxied = context.getBean(NotificationControlAccess.class);
            String result = proxied.withRecipientRead(42, scope -> {
                assertSame(connection, fixture.boundConnection());
                assertNull(tenantContext.getCatalog());
                assertEquals("[7,8]", scope.requireVisibilityForWorkspace(7).workspaceIdsJson());
                assertSame(member, scope.memberships().get(7));
                return "read";
            });
            assertEquals("read", result);
        }
        fixture.verifyOneCheckout();
        verify(connection).commit();
        verify(mapper).findRecipientMemberships(42);
        verify(mapper).findOrganizationWorkspaces(List.of(900));
        verifyNoInteractions(workspaceMapper);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void multiOrganizationScopesKeepEachSourceCeilingAndIncludeInactivePeers() {
        NotificationMembership first = member(7, 900, "active", "suspended", "active");
        NotificationMembership pending = member(8, 900, "pending", "active", "active");
        NotificationMembership foreign = member(20, 901, "active", "active", "tearing_down");
        List<Workspace> peers = List.of(workspace(21, 901), workspace(20, 901),
            workspace(9, 900), workspace(8, 900), workspace(7, 900));
        when(mapper.findRecipientMemberships(42)).thenReturn(List.of(foreign, pending, first));
        when(mapper.findOrganizationWorkspaces(List.of(900, 901))).thenReturn(peers);

        NotificationRecipientScope scope = readScope();

        assertEquals(42, scope.recipientId());
        assertEquals(List.of(7, 8, 9), scope.requireVisibilityForWorkspace(7).workspaceIds());
        assertSame(scope.requireVisibilityForWorkspace(7), scope.requireVisibilityForWorkspace(8));
        assertEquals(List.of(20, 21), scope.requireVisibilityForWorkspace(20).workspaceIds());
        assertEquals("Workspace 7", scope.memberships().get(7).workspaceName());
        assertTrue(first.admits("task.due"));
        assertFalse(first.permitsWorkspaceFilter());
        assertTrue(pending.admits("workspace.join"));
        assertFalse(pending.admits("task.due"));
        assertFalse(pending.permitsWorkspaceFilter());
        assertFalse(foreign.permitsWorkspaceFilter());
        assertTrue(member(30, 902, "active", "active", "active").permitsWorkspaceFilter());
        assertFalse(member(31, 902, "inactive", "active", "active").admits("workspace.join"));
        var memberships = objectMapper.readTree(scope.membershipsJson());
        assertEquals(3, memberships.size());
        assertEquals(7, memberships.get(0).get("workspaceId").asInt());
        assertEquals("pending", memberships.get(1).get("status").asString());
        assertEquals(108L, memberships.get(1).get("membershipId").asLong());
        var pairs = objectMapper.readTree(scope.sourceWorkspaceScopesJson());
        assertEquals(8, pairs.size());
        for (var pair : pairs) {
            int source = pair.get("sourceWorkspaceId").asInt();
            int owner = pair.get("id").asInt();
            assertTrue(source == 20 ? owner >= 20 : owner < 20);
        }
        assertThrows(UnsupportedOperationException.class, () -> scope.memberships().clear());
        assertThrows(UnsupportedOperationException.class, () -> scope.visibilityBySourceWorkspace().clear());
        assertThrows(UnsupportedOperationException.class,
            () -> scope.requireVisibilityForWorkspace(7).workspaceIds().clear());
        assertThrows(TenantScopeSnapshotException.class, () -> scope.requireVisibilityForWorkspace(21));
    }

    @Test
    void emptyMembershipsSkipOrganizationReadAndEachOperationGetsFreshData() {
        when(mapper.findRecipientMemberships(42)).thenReturn(List.of());

        NotificationRecipientScope first = readScope();
        NotificationRecipientScope second = readScope();

        assertEquals("[]", first.membershipsJson());
        assertEquals("[]", second.sourceWorkspaceScopesJson());
        verify(mapper, times(2)).findRecipientMemberships(42);
        verify(mapper, never()).findOrganizationWorkspaces(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void pendingActivationIsVisibleOnlyInANewSnapshot() {
        NotificationMembership pending = member(7, 900, "pending", "active", "active");
        NotificationMembership active = member(7, 900, "active", "active", "active");
        List<Workspace> peers = List.of(workspace(7, 900));
        when(mapper.findRecipientMemberships(42)).thenReturn(List.of(pending)).thenReturn(List.of(active));
        when(mapper.findOrganizationWorkspaces(List.of(900))).thenReturn(peers);

        NotificationRecipientScope first = readScope();
        NotificationRecipientScope second = readScope();

        assertFalse(first.memberships().get(7).admits("task.due"));
        assertTrue(second.memberships().get(7).admits("task.due"));
        assertEquals("pending", first.memberships().get(7).status());
    }

    @Test
    void rejectsForeignRecipientRowsAndDuplicateOrMissingAnchors() {
        NotificationMembership wrongRecipient = new NotificationMembership(
            7, 43, "active", 107, 900, "Other", "active", "active");
        NotificationMembership member = member(7, 900, "active", "active", "active");
        List<Workspace> missingAnchor = List.of(workspace(8, 900));
        List<Workspace> foreignAnchor = List.of(workspace(7, 901));
        when(mapper.findRecipientMemberships(42)).thenReturn(List.of(wrongRecipient))
            .thenReturn(List.of(member, member)).thenReturn(List.of(member)).thenReturn(List.of(member));
        when(mapper.findOrganizationWorkspaces(List.of(900))).thenReturn(missingAnchor).thenReturn(foreignAnchor);

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThrows(TenantScopeSnapshotException.class, this::readScope);
        }
    }

    @Test
    void firstControlFailurePropagatesUnchangedAndRestoresRoute() {
        TenantScopeSnapshotException failure = new TenantScopeSnapshotException("unavailable");
        when(mapper.findRecipientMemberships(42)).thenThrow(failure);

        assertSame(failure, assertThrows(TenantScopeSnapshotException.class, this::readScope));
        assertNull(tenantContext.getCatalog());
        verify(mapper, never()).findOrganizationWorkspaces(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void plainReadsRefuseWritableTransactionsBeforeAnyControlRead() {
        new TransactionTemplate(transactions).executeWithoutResult(status ->
            assertThrows(IllegalStateException.class, () -> access.withRecipientRead(42, scope -> scope)));
        verifyNoInteractions(mapper);
    }

    @Test
    void frozenReadScopeRefusesReadCommittedBeforeAnyControlRead() {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.executeWithoutResult(status ->
            assertThrows(IllegalStateException.class, () -> access.withRecipientRead(42, scope -> scope)));
        verifyNoInteractions(mapper);
    }

    @Test
    void exactLocksRetainCallerConnectionAndReturnCurrentGenerationOrAbsence() throws Exception {
        NotificationMembershipAuthority pending = new NotificationMembershipAuthority(7, 42, "pending", 101);
        NotificationMembershipAuthority rejoined = new NotificationMembershipAuthority(7, 42, "active", 102);
        when(mapper.lockMembershipForShare(7, 42)).thenAnswer(call -> {
            assertSame(connection, fixture.boundConnection());
            return pending;
        });
        when(mapper.lockMembershipForUpdate(7, 42)).thenReturn(rejoined);
        when(mapper.lockActorForShare(81)).thenReturn(81);
        when(mapper.lockActorForShare(82)).thenReturn(null);

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertSame(pending, access.lockMembershipForShare(7, 42));
            assertSame(rejoined, access.lockMembershipForUpdate(7, 42));
            assertNull(access.lockMembershipForUpdate(8, 42));
            assertEquals(81, access.lockActorForShare(81));
            assertNull(access.lockActorForShare(82));
            assertNull(access.lockActorForShare(null));
            assertSame(connection, fixture.boundConnection());
            assertNull(tenantContext.getCatalog());
        });
        fixture.verifyOneCheckout();
        verify(mapper, never()).findRecipientMemberships(org.mockito.ArgumentMatchers.anyInt());
        verify(mapper, never()).lockActorForShare(0);
    }

    @Test
    void locksRefuseMissingOrReadOnlyTransactions() {
        assertThrows(IllegalStateException.class, () -> access.lockMembershipForShare(7, 42));
        assertThrows(IllegalStateException.class, () -> access.lockMembershipForUpdate(7, 42));
        assertThrows(IllegalStateException.class, () -> access.lockActorForShare(81));
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.setReadOnly(true);
        transaction.executeWithoutResult(status -> {
            assertThrows(IllegalStateException.class, () -> access.lockMembershipForShare(7, 42));
            assertThrows(IllegalStateException.class, () -> access.lockMembershipForUpdate(7, 42));
            assertThrows(IllegalStateException.class, () -> access.lockActorForShare(81));
        });
        verifyNoInteractions(mapper);
    }

    @Test
    void routedModeIncludingSharedPlacementRefusesReadsAndLocks() {
        when(resolver.isCatalogPerPlacement()).thenReturn(true);
        assertThrows(ServiceUnavailableException.class, this::readScope);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThrows(ServiceUnavailableException.class, () -> access.lockMembershipForShare(7, 42));
            assertThrows(ServiceUnavailableException.class, () -> access.lockMembershipForUpdate(7, 42));
            assertThrows(ServiceUnavailableException.class, () -> access.lockActorForShare(81));
        });
        verifyNoInteractions(mapper, workspaceMapper);
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void proxiedRoutedEntriesRefuseBeforeAnyConnectionCheckout(Entry entry) {
        when(resolver.isCatalogPerPlacement()).thenReturn(true);
        fixture.rejectCheckouts();

        try (AnnotationConfigApplicationContext context = proxiedContext()) {
            NotificationControlAccess proxied = context.getBean(NotificationControlAccess.class);
            assertTrue(AopUtils.isAopProxy(proxied));
            assertThrows(ServiceUnavailableException.class, () -> invoke(proxied, entry));
            assertEquals(0, fixture.checkoutAttempts());
        }
        verifyNoInteractions(mapper, workspaceMapper);
    }

    @ParameterizedTest
    @EnumSource(value = Entry.class, names = {"MEMBERSHIP_SHARE", "MEMBERSHIP_UPDATE", "ACTOR_SHARE"})
    void proxiedLocksRefuseMissingTransactionWithoutCheckout(Entry entry) {
        fixture.rejectCheckouts();

        try (AnnotationConfigApplicationContext context = proxiedContext()) {
            NotificationControlAccess proxied = context.getBean(NotificationControlAccess.class);
            assertTrue(AopUtils.isAopProxy(proxied));
            assertThrows(IllegalStateException.class, () -> invoke(proxied, entry));
            assertEquals(0, fixture.checkoutAttempts());
        }
        verifyNoInteractions(mapper, workspaceMapper);
    }

    @Test
    void proxiedReadRefusesInvalidArgumentsBeforeCheckout() {
        fixture.rejectCheckouts();

        try (AnnotationConfigApplicationContext context = proxiedContext()) {
            NotificationControlAccess proxied = context.getBean(NotificationControlAccess.class);
            assertTrue(AopUtils.isAopProxy(proxied));
            assertThrows(IllegalArgumentException.class, () -> proxied.withRecipientRead(0, scope -> scope));
            assertThrows(NullPointerException.class, () -> proxied.withRecipientRead(42, null));
            assertEquals(0, fixture.checkoutAttempts());
        }
        verifyNoInteractions(mapper, workspaceMapper);
    }

    private static Object invoke(NotificationControlAccess access, Entry entry) {
        return switch (entry) {
            case READ -> access.withRecipientRead(42, scope -> scope);
            case MEMBERSHIP_SHARE -> access.lockMembershipForShare(7, 42);
            case MEMBERSHIP_UPDATE -> access.lockMembershipForUpdate(7, 42);
            case ACTOR_SHARE -> access.lockActorForShare(81);
        };
    }

    private NotificationRecipientScope readScope() {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.setReadOnly(true);
        return transaction.execute(status -> access.withRecipientRead(42, scope -> scope));
    }

    private AnnotationConfigApplicationContext proxiedContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.registerBean("transactionManager", PlatformTransactionManager.class, () -> transactions);
        context.registerBean(NotificationControlAccess.class, () -> fixture.proxied(access));
        context.refresh();
        return context;
    }

    private static NotificationMembership member(
            int workspaceId, int orgId, String status, String workspaceState, String orgState) {
        return new NotificationMembership(workspaceId, 42, status, 100L + workspaceId,
            orgId, "Workspace " + workspaceId, workspaceState, orgState);
    }

    private static Workspace workspace(int id, int orgId) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        workspace.setOrgId(orgId);
        workspace.setName("Workspace " + id);
        return workspace;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration { }

    enum Entry { READ, MEMBERSHIP_SHARE, MEMBERSHIP_UPDATE, ACTOR_SHARE }
}
