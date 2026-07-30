package ooo.klae.connex.backend.services;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.services.TenantExportExecution.TrackedResource;

/** Applies the current export deadline and cancellation owner to MyBatis snapshot statements. */
@Component
@Intercepts({
    @Signature(
        type = StatementHandler.class,
        method = "query",
        args = {Statement.class, org.apache.ibatis.session.ResultHandler.class}),
    @Signature(
        type = StatementHandler.class,
        method = "queryCursor",
        args = {Statement.class})
})
public class TenantExportQueryCancellationInterceptor implements Interceptor {
    private static final ThreadLocal<TenantExportExecution> CURRENT = new ThreadLocal<>();

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        TenantExportExecution execution = CURRENT.get();
        if (execution == null) {
            return invocation.proceed();
        }
        Statement statement = invocation.getArgs()[0] instanceof Statement current
            ? current
            : null;
        if (statement == null) {
            throw new IllegalStateException("Tenant export query statement is unavailable");
        }
        statement.setQueryTimeout(execution.remainingQueryTimeoutSeconds());
        StatementOwnership ownership = new StatementOwnership(statement);
        TrackedResource cancellation = execution.track(ownership);
        Object result;
        try {
            result = invocation.proceed();
        } catch (Throwable failure) {
            throw closeNormally(ownership, cancellation, failure);
        }
        if (!"queryCursor".equals(invocation.getMethod().getName())) {
            Throwable failure = closeNormally(ownership, cancellation, null);
            if (failure != null) {
                throw failure;
            }
            return result;
        }
        if (!(result instanceof Cursor<?> cursor)) {
            throw closeNormally(
                ownership,
                cancellation,
                new IllegalStateException("Tenant export query cursor is unavailable"));
        }
        return new CancellationOwnedCursor(cursor, ownership, cancellation);
    }

    static Scope openScope(TenantExportExecution execution) {
        if (CURRENT.get() != null) {
            throw new IllegalStateException("Tenant export query scope is already active");
        }
        CURRENT.set(execution);
        return new Scope(execution);
    }

    private static Throwable closeNormally(
            StatementOwnership ownership,
            TrackedResource cancellation,
            Throwable primary) {
        Throwable failure = primary;
        try {
            ownership.closeNormally();
        } catch (Exception | Error exception) {
            failure = appendFailure(failure, exception);
        }
        if (ownership.isVerifiedClosed()) {
            cancellation.release();
        }
        return failure;
    }

    private static Throwable appendFailure(Throwable primary, Throwable failure) {
        if (primary == null) {
            return failure;
        }
        if (primary != failure) {
            primary.addSuppressed(failure);
        }
        return primary;
    }

    private static final class StatementOwnership implements AutoCloseable {
        private final Statement statement;
        private final Connection connection;
        private boolean verifiedClosed;

        private StatementOwnership(Statement statement) throws SQLException {
            this.statement = statement;
            connection = statement.getConnection();
            if (connection == null) {
                throw new SQLException("Tenant export query connection is unavailable");
            }
        }

        private synchronized void closeNormally() throws Exception {
            closeStatementAndVerify();
        }

        @Override
        public synchronized void close() throws Exception {
            abortConnectionAndVerify();
        }

        private void closeStatementAndVerify() throws Exception {
            if (verifiedClosed) {
                return;
            }
            Throwable failure = null;
            try {
                statement.close();
            } catch (Exception | Error exception) {
                failure = appendFailure(failure, exception);
            }
            try {
                verifiedClosed = statement.isClosed();
                if (!verifiedClosed) {
                    failure = appendFailure(
                        failure,
                        new SQLException("Tenant export statement closure was not confirmed"));
                }
            } catch (Exception | Error exception) {
                failure = appendFailure(failure, exception);
            }
            throwFailure(failure);
        }

        private void abortConnectionAndVerify() throws Exception {
            if (verifiedClosed) {
                return;
            }
            Throwable failure = null;
            boolean aborted = false;
            try {
                connection.abort(Runnable::run);
                aborted = true;
            } catch (Exception | Error exception) {
                failure = exception;
            }
            if (aborted) {
                try {
                    connection.close();
                } catch (Exception | Error exception) {
                    failure = appendFailure(failure, exception);
                }
            }
            try {
                verifiedClosed = connection.isClosed();
                if (!verifiedClosed) {
                    failure = appendFailure(
                        failure,
                        new SQLException("Tenant export connection closure was not confirmed"));
                }
            } catch (Exception | Error exception) {
                failure = appendFailure(failure, exception);
            }
            throwFailure(failure);
        }

        private static void throwFailure(Throwable failure) throws Exception {
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }

        private synchronized boolean isVerifiedClosed() {
            return verifiedClosed;
        }
    }

    private static final class CancellationOwnedCursor implements Cursor<Object> {
        private final Cursor<?> delegate;
        private final StatementOwnership ownership;
        private final TrackedResource cancellation;

        private CancellationOwnedCursor(
                Cursor<?> delegate,
                StatementOwnership ownership,
                TrackedResource cancellation) {
            this.delegate = delegate;
            this.ownership = ownership;
            this.cancellation = cancellation;
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public boolean isConsumed() {
            return delegate.isConsumed();
        }

        @Override
        public int getCurrentIndex() {
            return delegate.getCurrentIndex();
        }

        @Override
        public Iterator<Object> iterator() {
            Iterator<?> iterator = delegate.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public Object next() {
                    return iterator.next();
                }

                @Override
                public void remove() {
                    iterator.remove();
                }
            };
        }

        @Override
        public void close() throws IOException {
            Throwable failure = null;
            try {
                delegate.close();
            } catch (Exception | Error exception) {
                failure = exception;
            }
            failure = closeNormally(ownership, cancellation, failure);
            if (failure == null) {
                return;
            }
            if (failure instanceof IOException ioException) {
                throw ioException;
            }
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IOException("Tenant export cursor cleanup failed", failure);
        }
    }

    static final class Scope implements AutoCloseable {
        private final TenantExportExecution execution;

        private Scope(TenantExportExecution execution) {
            this.execution = execution;
        }

        @Override
        public void close() {
            if (CURRENT.get() != execution) {
                throw new IllegalStateException("Tenant export query scope ownership changed");
            }
            CURRENT.remove();
        }
    }
}
