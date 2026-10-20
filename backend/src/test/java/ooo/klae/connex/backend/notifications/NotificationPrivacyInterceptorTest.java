package ooo.klae.connex.backend.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyPublicationGuard;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWorkspacePrivacy;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.support.TestTransactionManager;

/** Notification filtering binds server authority afresh and never trusts caller parameters. */
class NotificationPrivacyInterceptorTest {
    @Test
    void sourceEligibilityUsesFreshControlGenerationBeforePagination() throws Throwable {
        AtomicLong generation = new AtomicLong(1);
        var interceptor = new NotificationPrivacyInterceptor((recipientId, workspaceId) -> {
            assertEquals(11, recipientId);
            return List.of(new AiAssistantWorkspacePrivacy(7, 3, generation.get()));
        });
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
        generation.set(2);
        invoke(interceptor, executor, statement, supplied);
        assertEquals(Map.of("recipientId", 11, "assistantWorkspaceGenerations", Map.of(7, 2L)), captured.get());
        assertEquals(Map.of(7, 999L), supplied.get("assistantWorkspaceGenerations"));
    }

    @Test
    void unrelatedStatementsKeepTheirParametersAndDoNotReadAuthority() throws Throwable {
        var interceptor = new NotificationPrivacyInterceptor((recipientId, workspaceId) -> {
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

    @Test
    void cutoffCommittingBetweenAuthorityReadAndQueryRejectsStaleMaterializedContent() throws Throwable {
        AtomicLong generation = new AtomicLong(1);
        CountDownLatch beforeQuery = new CountDownLatch(1);
        CountDownLatch committed = new CountDownLatch(1);
        var interceptor = new NotificationPrivacyInterceptor((recipientId, workspaceId) ->
                List.of(new AiAssistantWorkspacePrivacy(7, 3, generation.get())));
        Executor executor = mock(Executor.class);
        var statement = statement("findByIdForUpdate");
        when(executor.query(eq(statement), any(), any(), any())).thenAnswer(call -> {
            beforeQuery.countDown();
            assertTrue(committed.await(5, TimeUnit.SECONDS));
            return List.of("stale assistant-derived content");
        });
        try (var workers = Executors.newSingleThreadExecutor()) {
            var cutoff = workers.submit(() -> {
                try {
                    assertTrue(beforeQuery.await(5, TimeUnit.SECONDS));
                    new TransactionTemplate(new TestTransactionManager()).executeWithoutResult(status -> {
                        AiAssistantPrivacyPublicationGuard.retainInvalidation(3);
                        generation.incrementAndGet();
                    });
                    committed.countDown();
                } catch (InterruptedException failure) {
                    throw new AssertionError(failure);
                }
            });
            assertThrows(ConflictException.class, () -> invoke(interceptor, executor, statement, Map.of("recipientId", 11)));
            cutoff.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void removedCandidateAuthorityCannotReturnPreviouslyMaterializedContent() throws Throwable {
        var interceptor = new NotificationPrivacyInterceptor(new java.util.function.BiFunction<Integer, Integer,
                List<AiAssistantWorkspacePrivacy>>() {
            private boolean queried;
            @Override
            public List<AiAssistantWorkspacePrivacy> apply(Integer recipientId, Integer workspaceId) {
                if (queried) return List.of();
                queried = true;
                return List.of(new AiAssistantWorkspacePrivacy(7, 3, 1));
            }
        });
        Executor executor = mock(Executor.class);
        var statement = statement("findPage");
        when(executor.query(eq(statement), any(), any(), any())).thenReturn(List.of("stale content"));
        assertThrows(ConflictException.class, () -> invoke(interceptor, executor, statement, Map.of("recipientId", 11)));
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
