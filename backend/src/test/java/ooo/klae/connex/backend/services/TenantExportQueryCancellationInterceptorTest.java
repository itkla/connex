package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.ResultHandler;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;

class TenantExportQueryCancellationInterceptorTest {

    @Test
    void ordinaryQueryReleasesStatementCancellationOnReturn() throws Throwable {
        try (ExecutionExecutors executors = new ExecutionExecutors()) {
            Statement statement = mock(Statement.class);
            Connection connection = connectionFor(statement);
            StatementHandler handler = mock(StatementHandler.class);
            List<Object> rows = List.of(new Object());
            when(handler.query(statement, null)).thenReturn(rows);
            when(statement.isClosed()).thenReturn(true);
            TenantExportExecution execution = execution(executors);
            TenantExportQueryCancellationInterceptor interceptor =
                new TenantExportQueryCancellationInterceptor();
            Method query = StatementHandler.class.getMethod(
                "query",
                Statement.class,
                ResultHandler.class);

            Object result;
            try (TenantExportQueryCancellationInterceptor.Scope ignored =
                    TenantExportQueryCancellationInterceptor.openScope(execution)) {
                result = interceptor.intercept(
                    new Invocation(handler, query, new Object[] {statement, null}));
            }
            execution.cancel();
            execution.writerFinished(null);

            assertSame(rows, result);
            verify(statement, never()).cancel();
            verify(statement).close();
            verify(statement).isClosed();
            verify(connection, never()).abort(any());
        }
    }

    @Test
    void queryCursorRetainsStatementCancellationUntilCursorClose() throws Throwable {
        try (ExecutionExecutors executors = new ExecutionExecutors()) {
            Statement statement = mock(Statement.class);
            Connection connection = connectionFor(statement);
            StatementHandler handler = mock(StatementHandler.class);
            TestCursor delegate = new TestCursor();
            when(handler.queryCursor(statement)).thenReturn(delegate);
            when(connection.isClosed()).thenReturn(true);
            TenantExportExecution execution = execution(executors);
            TenantExportQueryCancellationInterceptor interceptor =
                new TenantExportQueryCancellationInterceptor();
            Method queryCursor = StatementHandler.class.getMethod(
                "queryCursor",
                Statement.class);

            Object result;
            try (TenantExportQueryCancellationInterceptor.Scope ignored =
                    TenantExportQueryCancellationInterceptor.openScope(execution)) {
                result = interceptor.intercept(
                    new Invocation(handler, queryCursor, new Object[] {statement}));
            }
            assertTrue(result instanceof Cursor<?>);

            execution.cancel();
            execution.writerFinished(null);

            verify(connection).abort(any());
            verify(connection).close();
            verify(connection).isClosed();
            verify(statement, never()).cancel();
            verify(statement, never()).close();
        }
    }

    @Test
    void normallyClosedQueryCursorReleasesCancellationOwnership() throws Throwable {
        try (ExecutionExecutors executors = new ExecutionExecutors()) {
            Statement statement = mock(Statement.class);
            Connection connection = connectionFor(statement);
            StatementHandler handler = mock(StatementHandler.class);
            TestCursor delegate = new TestCursor();
            when(handler.queryCursor(statement)).thenReturn(delegate);
            when(statement.isClosed()).thenReturn(true);
            TenantExportExecution execution = execution(executors);
            TenantExportQueryCancellationInterceptor interceptor =
                new TenantExportQueryCancellationInterceptor();
            Method queryCursor = StatementHandler.class.getMethod(
                "queryCursor",
                Statement.class);

            Object result;
            try (TenantExportQueryCancellationInterceptor.Scope ignored =
                    TenantExportQueryCancellationInterceptor.openScope(execution)) {
                result = interceptor.intercept(
                    new Invocation(handler, queryCursor, new Object[] {statement}));
            }
            if (!(result instanceof Cursor<?> cursor)) {
                throw new AssertionError("Interceptor did not return a cursor");
            }
            cursor.close();
            execution.cancel();
            execution.writerFinished(null);

            assertTrue(delegate.closed());
            verify(statement, never()).cancel();
            verify(statement).close();
            verify(statement).isClosed();
            verify(connection, never()).abort(any());
        }
    }

    @Test
    void failedCursorCloseRetainsStatementOwnershipForWriterCleanup() throws Throwable {
        try (ExecutionExecutors executors = new ExecutionExecutors()) {
            Statement statement = mock(Statement.class);
            Connection connection = connectionFor(statement);
            StatementHandler handler = mock(StatementHandler.class);
            TestCursor delegate = new TestCursor(new IOException("cursor close failed"));
            when(handler.queryCursor(statement)).thenReturn(delegate);
            when(statement.isClosed()).thenReturn(true);
            TenantExportExecution execution = execution(executors);
            TenantExportQueryCancellationInterceptor interceptor =
                new TenantExportQueryCancellationInterceptor();
            Method queryCursor = StatementHandler.class.getMethod(
                "queryCursor",
                Statement.class);

            Object result;
            try (TenantExportQueryCancellationInterceptor.Scope ignored =
                    TenantExportQueryCancellationInterceptor.openScope(execution)) {
                result = interceptor.intercept(
                    new Invocation(handler, queryCursor, new Object[] {statement}));
            }
            if (!(result instanceof Cursor<?> cursor)) {
                throw new AssertionError("Interceptor did not return a cursor");
            }

            assertThrows(IOException.class, cursor::close);
            execution.cancel();
            execution.writerFinished(null);

            verify(statement, never()).cancel();
            verify(statement).close();
            verify(statement).isClosed();
            verify(connection, never()).abort(any());
        }
    }

    @Test
    void uncertainNormalCursorClosureRetainsOwnershipForCancellationRetry()
            throws Throwable {
        try (ExecutionExecutors executors = new ExecutionExecutors()) {
            SQLException firstCloseFailure = new SQLException("close uncertain");
            Statement statement = mock(Statement.class);
            Connection connection = connectionFor(statement);
            doThrow(firstCloseFailure).doNothing().when(statement).close();
            when(statement.isClosed()).thenReturn(false);
            when(connection.isClosed()).thenReturn(true);
            StatementHandler handler = mock(StatementHandler.class);
            when(handler.queryCursor(statement)).thenReturn(new TestCursor());
            TenantExportExecution execution = execution(executors);
            TenantExportQueryCancellationInterceptor interceptor =
                new TenantExportQueryCancellationInterceptor();
            Method queryCursor = StatementHandler.class.getMethod(
                "queryCursor",
                Statement.class);

            Object result;
            try (TenantExportQueryCancellationInterceptor.Scope ignored =
                    TenantExportQueryCancellationInterceptor.openScope(execution)) {
                result = interceptor.intercept(
                    new Invocation(handler, queryCursor, new Object[] {statement}));
            }
            if (!(result instanceof Cursor<?> cursor)) {
                throw new AssertionError("Interceptor did not return a cursor");
            }

            IOException thrown = assertThrows(IOException.class, cursor::close);
            assertSame(firstCloseFailure, thrown.getCause());
            execution.cancel();
            execution.writerFinished(null);

            verify(connection).abort(any());
            verify(connection).close();
            verify(connection).isClosed();
            verify(statement).close();
            verify(statement).isClosed();
        }
    }

    @Test
    void connectionAbortFailureRemainsPrimaryWhenWriterCleanupRetries() throws Throwable {
        try (ExecutionExecutors executors = new ExecutionExecutors()) {
            SQLException abortFailure = new SQLException("abort failed");
            Statement statement = mock(Statement.class);
            Connection connection = connectionFor(statement);
            doThrow(abortFailure).doNothing().when(connection).abort(any());
            when(connection.isClosed()).thenReturn(false, true);
            StatementHandler handler = mock(StatementHandler.class);
            when(handler.queryCursor(statement)).thenReturn(new TestCursor());
            TenantExportExecution execution = execution(executors);
            TenantExportQueryCancellationInterceptor interceptor =
                new TenantExportQueryCancellationInterceptor();
            Method queryCursor = StatementHandler.class.getMethod(
                "queryCursor",
                Statement.class);

            try (TenantExportQueryCancellationInterceptor.Scope ignored =
                    TenantExportQueryCancellationInterceptor.openScope(execution)) {
                interceptor.intercept(
                    new Invocation(handler, queryCursor, new Object[] {statement}));
            }
            execution.cancel();

            ServiceUnavailableException thrown = assertThrows(
                ServiceUnavailableException.class,
                () -> execution.writerFinished(null));

            assertSame(abortFailure, thrown.getCause());
            verify(connection, times(2)).abort(any());
            verify(connection).close();
            verify(connection, times(2)).isClosed();
            verify(statement, never()).cancel();
            verify(statement, never()).close();
        }
    }

    private static Connection connectionFor(Statement statement) throws SQLException {
        Connection connection = mock(Connection.class);
        when(statement.getConnection()).thenReturn(connection);
        return connection;
    }

    private static TenantExportExecution execution(ExecutionExecutors executors)
            throws Exception {
        TenantExportExecution execution = new TenantExportExecution(
            Duration.ofSeconds(5),
            executors.cancellation(),
            failure -> failure);
        execution.armDeadline(executors.deadline());
        execution.begin();
        return execution;
    }

    private static final class TestCursor implements Cursor<Object> {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final IOException closeFailure;

        private TestCursor() {
            this(null);
        }

        private TestCursor(IOException closeFailure) {
            this.closeFailure = closeFailure;
        }

        @Override
        public boolean isOpen() {
            return !closed.get();
        }

        @Override
        public boolean isConsumed() {
            return true;
        }

        @Override
        public int getCurrentIndex() {
            return -1;
        }

        @Override
        public Iterator<Object> iterator() {
            return List.of().iterator();
        }

        @Override
        public void close() throws IOException {
            if (closeFailure != null && !closed.getAndSet(true)) {
                throw closeFailure;
            }
            closed.set(true);
        }

        private boolean closed() {
            return closed.get();
        }
    }

    private static final class ExecutionExecutors implements AutoCloseable {
        private final ScheduledThreadPoolExecutor deadline =
            new ScheduledThreadPoolExecutor(1);
        private final ThreadPoolExecutor cancellation = new ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.NANOSECONDS,
            new ArrayBlockingQueue<>(4));

        private ExecutionExecutors() {
            deadline.setRemoveOnCancelPolicy(true);
            cancellation.prestartAllCoreThreads();
        }

        private ScheduledThreadPoolExecutor deadline() {
            return deadline;
        }

        private ThreadPoolExecutor cancellation() {
            return cancellation;
        }

        @Override
        public void close() {
            deadline.shutdownNow();
            cancellation.shutdownNow();
        }
    }
}
