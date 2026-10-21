package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.ResourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.support.MockJdbcTransactions;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Exercises operation boundaries with a recording transaction manager and executing callbacks. */
class AttachmentQueryReadServiceTest {
    private final AttachmentMapper attachments = mock(AttachmentMapper.class);
    private final AttachmentQueryControlAccess control = mock(AttachmentQueryControlAccess.class);
    private final TenantWorkScope scope = mock(TenantWorkScope.class);
    private final RecordingTransactions transactions = new RecordingTransactions();
    private final AttachmentQueryReadService service = new AttachmentQueryReadService(attachments, control, scope, transactions);

    @AfterEach
    void clearThreadState() {
        TransactionSynchronizationManager.unbindResourceIfPossible(transactions.resource);
        TransactionSynchronizationManager.clear();
    }

    @Test
    void pageAndCountUseOneReadOnlyRepeatableReadTransactionAndOneFactsSnapshot() {
        var row = new Attachment();
        when(attachments.getQueryUserIds(7, 11, true, true)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,
                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            return List.of(8, 9);
        });
        when(control.userFactsJson(List.of(8, 9), "%name%" )).thenReturn("facts");
        when(attachments.getPage(7, 11, "%name%", "name", null, null, null, true, 24, 24, "facts"))
            .thenReturn(List.of(row));
        when(attachments.countPage(7, 11, "%name%", null, null, null, true, "facts")).thenReturn(25L);

        var page = service.getPage(7, 11, "%name%", "name", null, null, null, true, 24, 24);

        assertEquals(List.of(row), page.items());
        assertEquals(25, page.total());
        assertEquals(1, transactions.begins);
        assertEquals(1, transactions.commits);
        var order = inOrder(attachments, control);
        order.verify(attachments).getQueryUserIds(7, 11, true, true);
        order.verify(control).userFactsJson(List.of(8, 9), "%name%");
        order.verify(attachments).getPage(7, 11, "%name%", "name", null, null, null, true, 24, 24, "facts");
        order.verify(attachments).countPage(7, 11, "%name%", null, null, null, true, "facts");
        verifyNoMoreInteractions(attachments, control);
    }

    @Test
    void searchUsesOnlyUploaderCandidatesAndFacetsOnlyTargets() {
        when(attachments.getQueryUserIds(7, 11, true, false)).thenReturn(List.of());
        when(attachments.getQueryUserIds(7, 11, false, true)).thenReturn(List.of());
        when(control.userFactsJson(List.of(), "%file%" )).thenReturn("[]");
        when(control.userFactsJson(List.of(), null)).thenReturn("[]");
        when(attachments.search(7, "%file%", 11, "[]")).thenReturn(List.of());
        assertTrue(service.search(7, "%file%", 11).isEmpty());
        assertEquals(0, service.facets(7, 11).orphaned());
        verify(attachments).countsBySource(7, 11);
        verify(attachments).countsByKind(7, 11);
        verify(attachments).countsByTag(7, 11);
        verify(attachments).countOrphaned(7, 11, "[]");
        verify(attachments).totalCount(7, 11);
        verify(attachments).totalSize(7, 11);
        assertEquals(2, transactions.commits);
    }

    @Test
    void snapshotRefusalRollsBackWithoutResultStatements() {
        when(attachments.getQueryUserIds(7, 11, true, false)).thenReturn(List.of(8));
        var refusal = new TenantScopeSnapshotException("refused");
        when(control.userFactsJson(List.of(8), "%file%" )).thenThrow(refusal);
        assertSame(refusal, assertThrows(TenantScopeSnapshotException.class,
            () -> service.search(7, "%file%", 11)));
        assertEquals(1, transactions.rollbacks);
        verify(attachments, never()).search(anyInt(), any(), anyInt(), any());
    }

    @Test
    void routedTransactionRefusesBeforeDiscoveryControlOrAnotherTransaction() {
        var catalogs = mock(TenantCatalogResolver.class);
        when(catalogs.isCatalogPerPlacement()).thenReturn(true);
        var realScope = new TenantWorkScope(new TenantContext(), catalogs, mock(WorkspaceMapper.class),
            mock(ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper.class),
            mock(TenantWorkspaceScopeControlOperations.class));
        var routed = new AttachmentQueryReadService(attachments, control, realScope, transactions);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThrows(TenantScopeSnapshotException.class, () -> routed.search(7, "%file%", 11));
        assertThrows(TenantScopeSnapshotException.class,
            () -> routed.getPage(7, 11, null, null, null, null, null, null, 24, 0));
        assertThrows(TenantScopeSnapshotException.class, () -> routed.facets(7, 11));
        assertEquals(0, transactions.begins);
        verifyNoInteractions(attachments, control);
    }

    @Test
    void incompatibleExistingIsolationRefusesBeforeDiscovery() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThrows(TenantScopeSnapshotException.class, () -> service.search(7, "%file%", 11));
        verifyNoInteractions(attachments, control);
    }

    @Test
    void defaultIsolationUsesTheExistingBoundConnectionAndKeepsItsSnapshot() throws Exception {
        var connection = mock(Connection.class);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        bindExisting(connection);
        when(attachments.getQueryUserIds(7, 11, true, false)).thenReturn(List.of());
        when(control.userFactsJson(List.of(), "%file%")).thenReturn("[]");
        when(attachments.search(7, "%file%", 11, "[]")).thenReturn(List.of());

        assertTrue(service.search(7, "%file%", 11).isEmpty());

        verify(connection).getTransactionIsolation();
        verifyNoMoreInteractions(connection);
        assertEquals(0, transactions.begins);
        assertEquals(0, transactions.commits);
    }

    @Test
    void defaultIsolationRejectsReadCommittedAndUnverifiableConnections() throws Exception {
        var connection = mock(Connection.class);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        bindExisting(connection);
        assertThrows(TenantScopeSnapshotException.class, () -> service.search(7, "%file%", 11));
        var failure = new SQLException("isolation unavailable");
        when(connection.getTransactionIsolation()).thenThrow(failure);
        assertSame(failure, assertThrows(TenantScopeSnapshotException.class,
            () -> service.search(7, "%file%", 11)).getCause());
        TransactionSynchronizationManager.unbindResource(transactions.resource);
        assertThrows(TenantScopeSnapshotException.class, () -> service.search(7, "%file%", 11));
        verifyNoInteractions(attachments, control);
        assertEquals(0, transactions.begins);
    }

    private void bindExisting(Connection connection) {
        transactions.existing = true;
        TransactionSynchronizationManager.bindResource(transactions.resource, new ConnectionHolder(connection));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    }

    @ParameterizedTest(name = "{0}, outer={1}")
    @MethodSource("routedOperations")
    void proxiedRoutedOperationsRefuseBeforeAnyAdditionalCheckout(QueryOperation operation, OuterTransaction outer)
            throws Exception {
        var jdbc = new MockJdbcTransactions(Connection.TRANSACTION_REPEATABLE_READ);
        var catalogs = mock(TenantCatalogResolver.class);
        when(catalogs.isCatalogPerPlacement()).thenReturn(true);
        var realScope = new TenantWorkScope(new TenantContext(), catalogs, mock(WorkspaceMapper.class),
            mock(ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper.class),
            mock(TenantWorkspaceScopeControlOperations.class));
        var proxied = proxy(realScope, jdbc);
        assertTrue(AopUtils.isAopProxy(proxied));

        if (outer == OuterTransaction.NONE) {
            assertThrows(TenantScopeSnapshotException.class, () -> operation.execute(proxied));
            assertEquals(0, jdbc.checkouts());
            assertEquals(0, jdbc.isolationReads());
        } else {
            var transaction = new TransactionTemplate(jdbc.manager());
            transaction.setReadOnly(outer == OuterTransaction.READ_ONLY);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            transaction.executeWithoutResult(status -> {
                assertEquals(1, jdbc.checkouts());
                Object resource = TransactionSynchronizationManager.getResource(jdbc.manager().getResourceFactory());
                int inspections = jdbc.isolationReads();
                assertThrows(TenantScopeSnapshotException.class, () -> operation.execute(proxied));
                assertEquals(1, jdbc.checkouts());
                assertEquals(inspections, jdbc.isolationReads());
                assertSame(resource, TransactionSynchronizationManager.getResource(jdbc.manager().getResourceFactory()));
                assertFalse(status.isRollbackOnly());
            });
        }
        verifyNoInteractions(attachments, control);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("callerTransactions")
    void proxiedSingleDatabaseReadsRequireReadOnlyRepeatableReadCallers(String description,
            int isolation, boolean readOnly, int physicalIsolation, boolean allowed) throws Exception {
        var jdbc = new MockJdbcTransactions(physicalIsolation);
        var proxied = proxy(scope, jdbc);
        if (allowed) {
            when(attachments.getQueryUserIds(7, 11, true, false)).thenReturn(List.of());
            when(control.userFactsJson(List.of(), "%file%")).thenReturn("[]");
            when(attachments.search(7, "%file%", 11, "[]")).thenReturn(List.of());
        }
        var transaction = new TransactionTemplate(jdbc.manager());
        transaction.setReadOnly(readOnly);
        transaction.setIsolationLevel(isolation);
        transaction.executeWithoutResult(status -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(readOnly, TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            Object resource = TransactionSynchronizationManager.getResource(jdbc.manager().getResourceFactory());
            int inspections = jdbc.isolationReads();
            if (allowed) {
                assertTrue(proxied.search(7, "%file%", 11).isEmpty());
                verify(attachments).search(7, "%file%", 11, "[]");
            } else {
                assertThrows(TenantScopeSnapshotException.class, () -> proxied.search(7, "%file%", 11));
                verifyNoInteractions(attachments, control);
            }
            if (!readOnly) {
                assertEquals(inspections, jdbc.isolationReads());
            }
            assertEquals(1, jdbc.checkouts());
            assertSame(resource, TransactionSynchronizationManager.getResource(jdbc.manager().getResourceFactory()));
            assertFalse(status.isRollbackOnly());
        });
    }

    @Test
    void writableCallerRefusesEveryEntryBeforeDiscoveryOrIsolationInspection() {
        var connection = mock(Connection.class);
        bindExisting(connection);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        for (QueryOperation operation : QueryOperation.values()) {
            assertThrows(TenantScopeSnapshotException.class, () -> operation.execute(service));
        }
        verifyNoInteractions(attachments, control, connection);
        assertEquals(0, transactions.begins);
    }

    private AttachmentQueryReadService proxy(TenantWorkScope tenantScope, MockJdbcTransactions jdbc) {
        var proxy = new ProxyFactory(new AttachmentQueryReadService(attachments, control, tenantScope, jdbc.manager()));
        proxy.addAdvice(new TransactionInterceptor(jdbc.manager(), new AnnotationTransactionAttributeSource()));
        return (AttachmentQueryReadService) proxy.getProxy();
    }

    private static Stream<Arguments> routedOperations() {
        return Stream.of(QueryOperation.values()).flatMap(operation -> Stream.of(OuterTransaction.values())
            .map(outer -> Arguments.of(operation, outer)));
    }

    private static Stream<Arguments> callerTransactions() {
        return Stream.of(
            Arguments.of("read-only RR", Connection.TRANSACTION_REPEATABLE_READ, true, Connection.TRANSACTION_REPEATABLE_READ, true),
            Arguments.of("writable RR", Connection.TRANSACTION_REPEATABLE_READ, false, Connection.TRANSACTION_REPEATABLE_READ, false),
            Arguments.of("read-only DEFAULT at RR", TransactionDefinition.ISOLATION_DEFAULT, true, Connection.TRANSACTION_REPEATABLE_READ, true),
            Arguments.of("writable DEFAULT at RR", TransactionDefinition.ISOLATION_DEFAULT, false, Connection.TRANSACTION_REPEATABLE_READ, false),
            Arguments.of("read-only RC", Connection.TRANSACTION_READ_COMMITTED, true, Connection.TRANSACTION_REPEATABLE_READ, false),
            Arguments.of("writable RC", Connection.TRANSACTION_READ_COMMITTED, false, Connection.TRANSACTION_REPEATABLE_READ, false),
            Arguments.of("read-only DEFAULT at RC", TransactionDefinition.ISOLATION_DEFAULT, true, Connection.TRANSACTION_READ_COMMITTED, false));
    }

    private enum OuterTransaction { NONE, READ_ONLY, WRITABLE }

    private enum QueryOperation {
        SEARCH, PAGE_AND_COUNT, FACETS;

        void execute(AttachmentQueryReadService target) {
            switch (this) {
                case SEARCH -> target.search(7, "%file%", 11);
                case PAGE_AND_COUNT -> target.getPage(7, 11, null, null, null, null, null, null, 24, 0);
                case FACETS -> target.facets(7, 11);
            }
        }
    }

    private static final class RecordingTransactions extends AbstractPlatformTransactionManager implements ResourceTransactionManager {
        final Object resource = new Object();
        boolean existing;
        int begins;
        int commits;
        int rollbacks;
        @Override public Object getResourceFactory() { return resource; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected boolean isExistingTransaction(Object transaction) { return existing; }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            assertTrue(definition.isReadOnly());
            assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ, definition.getIsolationLevel());
            begins++;
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
