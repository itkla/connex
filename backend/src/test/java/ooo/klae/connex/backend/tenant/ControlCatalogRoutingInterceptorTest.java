package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;

import org.apache.ibatis.executor.BatchExecutor;
import org.apache.ibatis.executor.CachingExecutor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ControlCatalogRoutingInterceptorTest {

    private static final String CONTROL_STATEMENT =
        "ooo.klae.connex.backend.mappers.WorkspaceMapper.getRole";
    private static final String TENANT_STATEMENT =
        "ooo.klae.connex.backend.mappers.PersonMapper.getPersonById";
    private static final String TENANT_CATALOG = "cnx_tenant";
    private static final Method UPDATE_METHOD;
    private static final Method QUERY_CURSOR_METHOD;

    static {
        try {
            UPDATE_METHOD = Executor.class.getMethod(
                "update", MappedStatement.class, Object.class);
            QUERY_CURSOR_METHOD = Executor.class.getMethod(
                "queryCursor",
                MappedStatement.class,
                Object.class,
                RowBounds.class);
        } catch (NoSuchMethodException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @Mock private Executor executor;
    @Mock private Transaction transaction;
    @Mock private ControlCatalogConnection connection;
    @Mock private MappedStatement statement;

    private final TenantContext tenantContext = new TenantContext();

    @AfterEach
    void clearContext() {
        tenantContext.clear();
    }

    @Test
    void routesControlStatementAndRestoresBeforeReturning() throws Throwable {
        ControlCatalogRoutingInterceptor interceptor =
            new ControlCatalogRoutingInterceptor(tenantContext, true);
        Object parameter = new Object();
        when(statement.getId()).thenReturn(CONTROL_STATEMENT);
        when(executor.getTransaction()).thenReturn(transaction);
        when(transaction.getConnection()).thenReturn(connection);
        when(connection.enterControlCatalog()).thenReturn(TENANT_CATALOG);
        when(executor.update(statement, parameter)).thenReturn(7);

        Object result = interceptor.intercept(
            new Invocation(executor, UPDATE_METHOD, new Object[] { statement, parameter }));

        assertEquals(7, result);
        InOrder order = inOrder(connection, executor);
        order.verify(connection).enterControlCatalog();
        order.verify(executor).update(statement, parameter);
        order.verify(connection).restoreCatalog(TENANT_CATALOG);
    }

    @Test
    void tenantStatementNeverTouchesControlRouting() throws Throwable {
        ControlCatalogRoutingInterceptor interceptor =
            new ControlCatalogRoutingInterceptor(tenantContext, true);
        Object parameter = new Object();
        when(statement.getId()).thenReturn(TENANT_STATEMENT);
        when(executor.update(statement, parameter)).thenReturn(3);

        Object result = interceptor.intercept(
            new Invocation(executor, UPDATE_METHOD, new Object[] { statement, parameter }));

        assertEquals(3, result);
        verify(executor, never()).getTransaction();
    }

    @Test
    void singleDatabaseModeIsInert() throws Throwable {
        ControlCatalogRoutingInterceptor interceptor =
            new ControlCatalogRoutingInterceptor(tenantContext, false);
        Object parameter = new Object();
        when(executor.update(statement, parameter)).thenReturn(5);

        Object result = interceptor.intercept(
            new Invocation(executor, UPDATE_METHOD, new Object[] { statement, parameter }));

        assertEquals(5, result);
        verify(executor, never()).getTransaction();
    }

    @Test
    void physicalRegistryIncludesControlAndScopedControlExceptionsOnly() {
        ControlCatalogRoutingInterceptor interceptor =
            new ControlCatalogRoutingInterceptor(tenantContext, true);

        assertTrue(interceptor.routesToControlCatalog(CONTROL_STATEMENT));
        assertTrue(interceptor.routesToControlCatalog(
            "ooo.klae.connex.backend.mappers.AuditLogMapper.insert"));
        assertTrue(interceptor.routesToControlCatalog(
            "ooo.klae.connex.backend.mappers.RoleMapper.findPermissions"));
        assertFalse(interceptor.routesToControlCatalog(TENANT_STATEMENT));
    }

    @Test
    void routesControlOnlyStatementsFromMixedMapper() throws Throwable {
        ControlCatalogRoutingInterceptor interceptor =
            new ControlCatalogRoutingInterceptor(tenantContext, true);
        Object parameter = new Object();
        when(executor.getTransaction()).thenReturn(transaction);
        when(transaction.getConnection()).thenReturn(connection);
        when(connection.enterControlCatalog()).thenReturn(TENANT_CATALOG);
        when(executor.update(statement, parameter)).thenReturn(4);

        for (String statementId : java.util.List.of(
                "ooo.klae.connex.backend.mappers.NotificationMapper.getStateVersion",
                "ooo.klae.connex.backend.mappers.NotificationMapper.bumpStateVersions",
                "ooo.klae.connex.backend.mappers.NotificationMapper.lockRecipientMemberships",
                "ooo.klae.connex.backend.mappers.NotificationMapper.findWorkspaceRecipientIds")) {
            when(statement.getId()).thenReturn(statementId);

            assertEquals(
                4,
                interceptor.intercept(
                    new Invocation(
                        executor,
                        UPDATE_METHOD,
                        new Object[] { statement, parameter })));
        }

        verify(connection, times(4)).enterControlCatalog();
        verify(executor, times(4)).update(statement, parameter);
        verify(connection, times(4)).restoreCatalog(TENANT_CATALOG);
    }

    @Test
    void routedScopeFailsClosedWhenConnectionLacksRoutingCapability() throws Throwable {
        ControlCatalogRoutingInterceptor interceptor =
            new ControlCatalogRoutingInterceptor(tenantContext, true);
        Connection unrouted = org.mockito.Mockito.mock(Connection.class);
        tenantContext.set(1, 1, 1, "member", TENANT_CATALOG);
        when(statement.getId()).thenReturn(CONTROL_STATEMENT);
        when(executor.getTransaction()).thenReturn(transaction);
        when(transaction.getConnection()).thenReturn(unrouted);

        assertThrows(
            IllegalStateException.class,
            () -> interceptor.intercept(
                new Invocation(executor, UPDATE_METHOD, new Object[] { statement, new Object() })));
        verify(executor, never()).update(any(), any());
    }

    @Test
    void unresolvedControlStatementUsesTheDefaultConnectionDirectly() throws Throwable {
        ControlCatalogRoutingInterceptor interceptor =
            new ControlCatalogRoutingInterceptor(tenantContext, true);
        Connection unrouted = org.mockito.Mockito.mock(Connection.class);
        Object parameter = new Object();
        when(statement.getId()).thenReturn(CONTROL_STATEMENT);
        when(executor.getTransaction()).thenReturn(transaction);
        when(transaction.getConnection()).thenReturn(unrouted);
        when(executor.update(statement, parameter)).thenReturn(2);

        Object result = interceptor.intercept(
            new Invocation(executor, UPDATE_METHOD, new Object[] { statement, parameter }));

        assertEquals(2, result);
    }

    @Test
    void invocationFailureKeepsRestoreFailureSuppressed() throws Throwable {
        ControlCatalogRoutingInterceptor interceptor =
            new ControlCatalogRoutingInterceptor(tenantContext, true);
        Object parameter = new Object();
        SQLException invocationFailure = new SQLException("statement failed");
        SQLException restoreFailure = new SQLException("restore failed");
        when(statement.getId()).thenReturn(CONTROL_STATEMENT);
        when(executor.getTransaction()).thenReturn(transaction);
        when(transaction.getConnection()).thenReturn(connection);
        when(connection.enterControlCatalog()).thenReturn(TENANT_CATALOG);
        when(executor.update(statement, parameter)).thenThrow(invocationFailure);
        org.mockito.Mockito.doThrow(restoreFailure)
            .when(connection).restoreCatalog(TENANT_CATALOG);

        SQLException thrown = assertThrows(
            SQLException.class,
            () -> interceptor.intercept(
                new Invocation(executor, UPDATE_METHOD, new Object[] { statement, parameter })));

        assertSame(invocationFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(restoreFailure, thrown.getSuppressed()[0]);
    }

    @Test
    void restoreFailureAfterSuccessfulStatementPropagates() throws Throwable {
        ControlCatalogRoutingInterceptor interceptor =
            new ControlCatalogRoutingInterceptor(tenantContext, true);
        Object parameter = new Object();
        SQLException restoreFailure = new SQLException("restore failed");
        when(statement.getId()).thenReturn(CONTROL_STATEMENT);
        when(executor.getTransaction()).thenReturn(transaction);
        when(transaction.getConnection()).thenReturn(connection);
        when(connection.enterControlCatalog()).thenReturn(TENANT_CATALOG);
        when(executor.update(statement, parameter)).thenReturn(1);
        org.mockito.Mockito.doThrow(restoreFailure)
            .when(connection).restoreCatalog(TENANT_CATALOG);

        SQLException thrown = assertThrows(
            SQLException.class,
            () -> interceptor.intercept(
                new Invocation(executor, UPDATE_METHOD, new Object[] { statement, parameter })));

        assertSame(restoreFailure, thrown);
        verify(connection).restoreCatalog(TENANT_CATALOG);
    }

    @Test
    void batchExecutorFailsBeforeControlStatementIsQueued() throws Throwable {
        ControlCatalogRoutingInterceptor interceptor =
            new ControlCatalogRoutingInterceptor(tenantContext, true);
        when(statement.getId()).thenReturn(CONTROL_STATEMENT);
        when(transaction.getConnection()).thenReturn(connection);
        BatchExecutor batchExecutor = new BatchExecutor(new Configuration(), transaction);
        CachingExecutor cachingExecutor = new CachingExecutor(batchExecutor);
        Executor pluginWrapped = (Executor) new TenantScopeInterceptor(
            tenantContext, false, true).plugin(cachingExecutor);

        assertThrows(
            IllegalStateException.class,
            () -> interceptor.intercept(
                new Invocation(
                    pluginWrapped,
                    UPDATE_METHOD,
                    new Object[] { statement, new Object() })));
        verify(connection, never()).enterControlCatalog();
    }

    @Test
    void controlCursorFailsBeforeItCanEscapeTheCatalogScope() throws Throwable {
        ControlCatalogRoutingInterceptor interceptor =
            new ControlCatalogRoutingInterceptor(tenantContext, true);
        when(statement.getId()).thenReturn(CONTROL_STATEMENT);
        when(executor.getTransaction()).thenReturn(transaction);
        when(transaction.getConnection()).thenReturn(connection);

        assertThrows(
            IllegalStateException.class,
            () -> interceptor.intercept(
                new Invocation(
                    executor,
                    QUERY_CURSOR_METHOD,
                    new Object[] { statement, new Object(), RowBounds.DEFAULT })));
        verify(connection, never()).enterControlCatalog();
    }
}
