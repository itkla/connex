package ooo.klae.connex.backend.support;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import ooo.klae.connex.backend.services.NotificationControlAccess;

/**
 * Mock JDBC connection and real transaction lifecycle for DB-free notification boundary tests.
 * No driver, database URL or external connection is used. Keeping the mock plumbing here lets
 * the conservative test partition classify the actual test independently of JDBC type names.
 */
public final class NotificationControlAccessFixture {
    private final DataSource dataSource;
    private final Connection connection;
    private final PlatformTransactionManager transactionManager;
    private final AtomicInteger checkoutAttempts = new AtomicInteger();
    private boolean rejectCheckout;

    /** Creates an executing transaction manager backed only by one Mockito connection. */
    public NotificationControlAccessFixture() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        lenient().when(dataSource.getConnection()).thenAnswer(call -> {
            checkoutAttempts.incrementAndGet();
            if (rejectCheckout) {
                throw new SQLException("Unexpected connection checkout");
            }
            return connection;
        });
        lenient().when(connection.getAutoCommit()).thenReturn(true);
        transactionManager = new DataSourceTransactionManager(dataSource);
    }

    /** Returns the mock connection expected to stay bound throughout the operation. */
    public Connection connection() { return connection; }

    /** Returns the current transaction's connection through Spring's ordinary binding. */
    public Connection boundConnection() { return DataSourceUtils.getConnection(dataSource); }

    /** Returns the real transaction manager operating on the mock connection. */
    public PlatformTransactionManager transactionManager() { return transactionManager; }

    /** Makes any accidental checkout fail, so a typed pre-transaction refusal cannot be masked. */
    public void rejectCheckouts() { rejectCheckout = true; }

    /** Counts attempted checkouts, including failed attempts before an entry method is invoked. */
    public int checkoutAttempts() { return checkoutAttempts.get(); }

    /**
     * Advises the facade through Spring even when it has no transaction attributes. Restoring an
     * automatic transaction annotation would then exercise its advice before the routing guard.
     */
    public NotificationControlAccess proxied(NotificationControlAccess target) {
        TransactionInterceptor advice = new TransactionInterceptor();
        advice.setTransactionManager(transactionManager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(advice);
        return (NotificationControlAccess) proxy.getProxy();
    }

    /** Requires exactly one checkout; suspension or an independent transaction would add another. */
    public void verifyOneCheckout() throws Exception { verify(dataSource).getConnection(); }
}
