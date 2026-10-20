package ooo.klae.connex.backend.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;

/** Notification filtering binds server authority afresh and never trusts caller parameters. */
class NotificationPrivacyInterceptorTest {
    @Test
    void sourceEligibilityUsesFreshControlGenerationBeforePagination() throws Throwable {
        AtomicInteger reads = new AtomicInteger();
        var interceptor = new NotificationPrivacyInterceptor(() -> Map.of(7, (long) reads.incrementAndGet()));
        Executor executor = mock(Executor.class);
        var statement = statement("findPage");
        AtomicReference<Object> captured = new AtomicReference<>();
        when(executor.query(eq(statement), any(), any(), any())).thenAnswer(call -> {
            captured.set(call.getArgument(1));
            return List.of();
        });
        Map<String, Object> supplied = Map.of("recipientId", 11,
                "assistantWorkspaceGenerations", Map.of(7, 999L));
        invoke(interceptor, executor, statement, supplied);
        assertEquals(Map.of("recipientId", 11, "assistantWorkspaceGenerations", Map.of(7, 1L)), captured.get());
        invoke(interceptor, executor, statement, supplied);
        assertEquals(Map.of("recipientId", 11, "assistantWorkspaceGenerations", Map.of(7, 2L)), captured.get());
        assertEquals(Map.of(7, 999L), supplied.get("assistantWorkspaceGenerations"));
    }

    @Test
    void unrelatedStatementsKeepTheirParametersAndDoNotReadAuthority() throws Throwable {
        var interceptor = new NotificationPrivacyInterceptor(() -> {
            throw new AssertionError("An unrelated statement must not resolve chat authority");
        });
        Executor executor = mock(Executor.class);
        var statement = statement("getDatabaseUtcTimestamp");
        AtomicReference<Object> captured = new AtomicReference<>();
        when(executor.query(eq(statement), any(), any(), any())).thenAnswer(call -> {
            captured.set(call.getArgument(1));
            return List.of();
        });
        Map<String, Object> supplied = Map.of("recipientId", 11);
        invoke(interceptor, executor, statement, supplied);
        assertSame(supplied, captured.get());
    }

    private static MappedStatement statement(String id) {
        return new MappedStatement.Builder(new Configuration(),
                "ooo.klae.connex.backend.mappers.NotificationMapper." + id,
                new StaticSqlSource(new Configuration(), "SELECT 1"), SqlCommandType.SELECT).build();
    }

    private static void invoke(NotificationPrivacyInterceptor interceptor, Executor executor,
            MappedStatement statement, Map<String, Object> parameters) throws Throwable {
        var method = Executor.class.getMethod("query", MappedStatement.class, Object.class,
                RowBounds.class, ResultHandler.class);
        interceptor.intercept(new Invocation(executor, method,
                new Object[] {statement, parameters, RowBounds.DEFAULT, null}));
    }
}
