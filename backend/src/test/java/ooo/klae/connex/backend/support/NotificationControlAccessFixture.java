package ooo.klae.connex.backend.support;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.Connection;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Mock JDBC connection and real transaction lifecycle for DB-free notification boundary tests.
 * No driver, database URL or external connection is used. Keeping the mock plumbing here lets
 * the conservative test partition classify the actual test independently of JDBC type names.
 */
public final class NotificationControlAccessFixture {
    private final DataSource dataSource;
    private final Connection connection;
    private final PlatformTransactionManager transactionManager;

    /** Creates an executing transaction manager backed only by one Mockito connection. */
    public NotificationControlAccessFixture() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.getAutoCommit()).thenReturn(true);
        transactionManager = new DataSourceTransactionManager(dataSource);
    }

    /** Returns the mock connection expected to stay bound throughout the operation. */
    public Connection connection() { return connection; }

    /** Returns the current transaction's connection through Spring's ordinary binding. */
    public Connection boundConnection() { return DataSourceUtils.getConnection(dataSource); }

    /** Returns the real transaction manager operating on the mock connection. */
    public PlatformTransactionManager transactionManager() { return transactionManager; }

    /** Requires exactly one checkout; suspension or an independent transaction would add another. */
    public void verifyOneCheckout() throws Exception { verify(dataSource).getConnection(); }
}
