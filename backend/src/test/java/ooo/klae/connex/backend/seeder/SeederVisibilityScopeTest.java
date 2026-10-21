package ooo.klae.connex.backend.seeder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantContext;

/** Exercises the fresh-organization seeder ceiling without opening a database or a SIMPLE session. */
class SeederVisibilityScopeTest {
    @Test
    void everyBatchJunctionBindsOnlyItsGeneratedWorkspaceAndPreservesDeterministicCounts() {
        Map<Class<?>, Object> mappers = new HashMap<>();
        AtomicInteger ids = new AtomicInteger(1_000);
        try (var sessions = mockConstruction(SqlSessionTemplate.class, (session, context) -> {
            assertEquals(ExecutorType.BATCH, context.arguments().get(1));
            when(session.getMapper(any())).thenAnswer(invocation -> {
                Class<?> type = invocation.getArgument(0);
                return mappers.computeIfAbsent(type, key -> mock(key, call -> {
                    if (call.getMethod().getName().startsWith("insert") && call.getArguments().length == 1) {
                        Object bean = call.getArgument(0);
                        try {
                            try {
                                bean.getClass().getMethod("setId", int.class).invoke(bean, ids.incrementAndGet());
                            } catch (NoSuchMethodException absentPrimitiveSetter) {
                                bean.getClass().getMethod("setId", Integer.class).invoke(bean, ids.incrementAndGet());
                            }
                        } catch (NoSuchMethodException absentGeneratedId) {
                            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
                        }
                    }
                    return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
                }));
            });
        })) {
            TenantContext tenant = new TenantContext();
            SeederBatchWriter writer = new SeederBatchWriter(mock(SqlSessionFactory.class), new SeedDataGenerator(),
                tenant, mock(ApplicationEventPublisher.class));
            var first = writer.write(SeederProperties.Profile.SMALL, 853, 0, LocalDate.of(2026, 1, 15), List.of());
            var second = writer.write(SeederProperties.Profile.SMALL, 854, 1, LocalDate.of(2026, 1, 15), List.of());
            DealMapper deals = (DealMapper) mappers.get(DealMapper.class);
            long links = mockingDetails(deals).getInvocations().stream()
                .filter(call -> "addPerson".equals(call.getMethod().getName()))
                .peek(call -> assertEquals("[" + call.getArgument(0) + "]", call.getArgument(1)))
                .count();
            assertEquals(first.rowCounts().get("deal_person") + second.rowCounts().get("deal_person"), links);
            assertTrue(links > 0);
            assertEquals(20, first.rowCounts().get("deal"));
            assertEquals(20, second.rowCounts().get("deal"));
            assertEquals(2, mockingDetails(deals).getInvocations().stream()
                .filter(call -> "addPerson".equals(call.getMethod().getName()))
                .map(call -> call.getArgument(1)).distinct().count());
            verify((WorkspaceMapper) mappers.get(WorkspaceMapper.class), never()).findOrganizationWorkspacesForShare(anyInt());
            assertEquals(1, sessions.constructed().size());
            assertFalse(tenant.isResolved());
        }
    }

    @Test
    void aNonemptyBaseIsRefusedBeforeAnyBatchWrite() {
        try (var sessions = mockConstruction(SqlSessionTemplate.class)) {
            SeederBatchWriter writer = new SeederBatchWriter(mock(SqlSessionFactory.class), new SeedDataGenerator(),
                new TenantContext(), mock(ApplicationEventPublisher.class));
            assertThrows(IllegalArgumentException.class, () -> writer.write(
                SeederProperties.Profile.SMALL, 853, 0, LocalDate.of(2026, 1, 15), List.of(7)));
            verify(sessions.constructed().getFirst(), never()).flushStatements();
        }
    }

    @Test
    void aFailedWorkspaceRetainsTheExistingTransactionRollbackBoundary() {
        SeederBatchWriter writer = mock(SeederBatchWriter.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        SimpleTransactionStatus status = new SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(status);
        when(writer.write(any(), anyLong(), anyInt(), any(), anyList()))
            .thenAnswer(invocation -> {
                assertEquals(List.of(), invocation.getArgument(4));
                throw new IllegalStateException("Batch failed");
            });
        SeederService service = new SeederService(writer, transactions, "single-database");

        assertThrows(IllegalStateException.class, () -> service.seed(
            SeederProperties.Profile.SMALL, 853, 2, LocalDate.of(2026, 1, 15)));

        verify(transactions).rollback(status);
        verify(writer).write(any(), anyLong(), anyInt(), any(), anyList());
        verify(transactions, never()).commit(any());
    }
}
