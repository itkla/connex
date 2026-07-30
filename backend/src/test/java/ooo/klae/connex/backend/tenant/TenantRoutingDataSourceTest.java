package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenantRoutingDataSourceTest {

    private static final String DEFAULT_CATALOG = "connexdb";
    private static final String TENANT_CATALOG = "cnx_abc123";

    @Mock private DataSource delegate;
    @Mock private Connection connection;
    @Mock private Consumer<Connection> evictor;

    private final TenantContext tenantContext = new TenantContext();
    private TenantRoutingDataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        when(delegate.getConnection()).thenReturn(connection);
        dataSource = new TenantRoutingDataSource(delegate, tenantContext, DEFAULT_CATALOG, evictor);
    }

    @AfterEach
    void clearContext() {
        tenantContext.clear();
    }

    @Test
    void unresolvedContextPassesThroughUntouched() throws SQLException {
        assertSame(connection, dataSource.getConnection());
        verify(connection, never()).setCatalog(anyString());
    }

    @Test
    void sharedContextPassesThroughUntouched() throws SQLException {
        tenantContext.set(1, 1, 1, "owner", null);
        assertSame(connection, dataSource.getConnection());
        verify(connection, never()).setCatalog(anyString());
    }

    @Test
    void routedContextSwitchesAtCheckoutAndResetsOnClose() throws SQLException {
        tenantContext.set(1, 1, 1, "owner", TENANT_CATALOG);

        Connection routed = dataSource.getConnection();
        assertNotSame(connection, routed);
        verify(connection).setCatalog(TENANT_CATALOG);

        routed.close();
        InOrder order = inOrder(connection);
        order.verify(connection).setCatalog(DEFAULT_CATALOG);
        order.verify(connection).close();
        verify(evictor, never()).accept(any());
    }

    @Test
    void contextClearedBeforeCloseStillResets() throws SQLException {
        tenantContext.set(1, 1, 1, "owner", TENANT_CATALOG);
        Connection routed = dataSource.getConnection();
        tenantContext.clear();

        routed.close();
        verify(connection).setCatalog(DEFAULT_CATALOG);
        verify(connection).close();
    }

    @Test
    void doubleCloseIsANoOpAndNeverEvicts() throws SQLException {
        tenantContext.set(1, 1, 1, "owner", TENANT_CATALOG);
        Connection routed = dataSource.getConnection();

        routed.close();
        routed.close();
        verify(connection, times(1)).setCatalog(DEFAULT_CATALOG);
        verify(connection, times(1)).close();
        verify(evictor, never()).accept(any());
    }

    @Test
    void resetFailureEvictsAndStillCloses() throws SQLException {
        tenantContext.set(1, 1, 1, "owner", TENANT_CATALOG);
        Connection routed = dataSource.getConnection();
        doThrow(new SQLException("reset failed")).when(connection).setCatalog(DEFAULT_CATALOG);

        routed.close();
        verify(evictor).accept(connection);
        verify(connection).close();
    }

    @Test
    void checkoutSwitchFailureEvictsClosesAndPropagates() throws SQLException {
        tenantContext.set(1, 1, 1, "owner", TENANT_CATALOG);
        doThrow(new SQLException("switch failed")).when(connection).setCatalog(TENANT_CATALOG);

        assertThrows(SQLException.class, () -> dataSource.getConnection());
        InOrder order = inOrder(evictor, connection);
        order.verify(evictor).accept(connection);
        order.verify(connection).close();
    }

    @Test
    void wrapperDelegatesOtherCallsToTheRealConnection() throws SQLException {
        tenantContext.set(1, 1, 1, "owner", TENANT_CATALOG);
        Connection routed = dataSource.getConnection();

        routed.getAutoCommit();
        verify(connection).getAutoCommit();
    }

    @Test
    void controlCatalogScopeSwitchesAndRestoresTheTenantCatalog() throws SQLException {
        tenantContext.set(1, 1, 1, "owner", TENANT_CATALOG);
        when(connection.getCatalog()).thenReturn(TENANT_CATALOG, DEFAULT_CATALOG);
        ControlCatalogConnection routed =
            (ControlCatalogConnection) dataSource.getConnection();

        String previous = routed.enterControlCatalog();
        routed.restoreCatalog(previous);

        InOrder order = inOrder(connection);
        order.verify(connection).setCatalog(TENANT_CATALOG);
        order.verify(connection).getCatalog();
        order.verify(connection).setCatalog(DEFAULT_CATALOG);
        order.verify(connection).getCatalog();
        order.verify(connection).setCatalog(TENANT_CATALOG);
        verify(evictor, never()).accept(any());
    }

    @Test
    void nestedControlCatalogScopesRestoreInStackOrder() throws SQLException {
        tenantContext.set(1, 1, 1, "owner", TENANT_CATALOG);
        when(connection.getCatalog()).thenReturn(
            TENANT_CATALOG,
            DEFAULT_CATALOG,
            DEFAULT_CATALOG,
            DEFAULT_CATALOG);
        ControlCatalogConnection routed =
            (ControlCatalogConnection) dataSource.getConnection();

        String outer = routed.enterControlCatalog();
        String inner = routed.enterControlCatalog();
        routed.restoreCatalog(inner);
        routed.restoreCatalog(outer);

        assertSame(TENANT_CATALOG, outer);
        assertSame(DEFAULT_CATALOG, inner);
        verify(connection, times(1)).setCatalog(DEFAULT_CATALOG);
        verify(connection, times(2)).setCatalog(TENANT_CATALOG);
        verify(evictor, never()).accept(any());
    }

    @Test
    void controlCatalogSwitchFailureEvictsClosesAndPropagates() throws SQLException {
        tenantContext.set(1, 1, 1, "owner", TENANT_CATALOG);
        when(connection.getCatalog()).thenReturn(TENANT_CATALOG);
        ControlCatalogConnection routed =
            (ControlCatalogConnection) dataSource.getConnection();
        doThrow(new SQLException("control switch failed"))
            .when(connection).setCatalog(DEFAULT_CATALOG);

        assertThrows(SQLException.class, routed::enterControlCatalog);
        InOrder order = inOrder(evictor, connection);
        order.verify(evictor).accept(connection);
        order.verify(connection).close();
    }

    @Test
    void tenantCatalogRestoreFailureEvictsClosesAndPropagates() throws SQLException {
        tenantContext.set(1, 1, 1, "owner", TENANT_CATALOG);
        when(connection.getCatalog()).thenReturn(TENANT_CATALOG, DEFAULT_CATALOG);
        ControlCatalogConnection routed =
            (ControlCatalogConnection) dataSource.getConnection();
        String previous = routed.enterControlCatalog();
        doThrow(new SQLException("tenant restore failed"))
            .when(connection).setCatalog(TENANT_CATALOG);

        assertThrows(SQLException.class, () -> routed.restoreCatalog(previous));
        InOrder order = inOrder(evictor, connection);
        order.verify(evictor).accept(connection);
        order.verify(connection).close();
    }
}
