package ooo.klae.connex.backend.support;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyInt;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

/** Real JDBC transaction management over a mock connection, with no database or network access. */
public final class MockJdbcTransactions {
    private final AtomicInteger checkouts = new AtomicInteger();
    private final AtomicInteger isolationReads = new AtomicInteger();
    private final JdbcTransactionManager manager;

    /** Counts physical acquisition and isolation inspection while retaining JDBC isolation changes. */
    public MockJdbcTransactions(int initialIsolation) throws SQLException {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var isolation = new AtomicInteger(initialIsolation);
        when(source.getConnection()).thenAnswer(call -> {
            checkouts.incrementAndGet();
            return connection;
        });
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenAnswer(call -> {
            isolationReads.incrementAndGet();
            return isolation.get();
        });
        doAnswer(call -> {
            isolation.set(call.getArgument(0));
            return null;
        }).when(connection).setTransactionIsolation(anyInt());
        manager = new JdbcTransactionManager(source);
    }

    /** Returns the actual Spring transaction manager used by the test's advice and templates. */
    public JdbcTransactionManager manager() { return manager; }

    /** Returns the count of mock physical connection acquisitions. */
    public int checkouts() { return checkouts.get(); }

    /** Returns the count of JDBC isolation inspections. */
    public int isolationReads() { return isolationReads.get(); }
}
