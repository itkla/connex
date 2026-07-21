package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class LegacyWorkflowBackfillRunnerTest {

    @Mock private PlacementRegistry placementRegistry;
    @Mock private TenantWorkScope tenantWorkScope;
    @Mock private RuleMapper ruleMapper;
    @Mock private LegacyWorkflowBackfillTransaction backfillTransaction;
    @Mock private ApplicationArguments arguments;

    private LegacyWorkflowBackfillRunner runner;

    @BeforeEach
    void setUp() {
        runner = new LegacyWorkflowBackfillRunner(
            placementRegistry, tenantWorkScope, ruleMapper, backfillTransaction);
    }

    @Test
    void enumeratesEachCatalogThenRevalidatesWorkspacePlacementBeforeBackfill() {
        AtomicBoolean unrouted = new AtomicBoolean();
        AtomicReference<String> pinnedCatalog = new AtomicReference<>();
        AtomicBoolean defaultPinned = new AtomicBoolean();

        when(tenantWorkScope.unrouted(any())).thenAnswer(invocation -> {
            unrouted.set(true);
            try {
                return invocation.<Supplier<?>>getArgument(0).get();
            } finally {
                unrouted.set(false);
            }
        });
        when(placementRegistry.activeCatalogs()).thenAnswer(invocation -> {
            assertTrue(unrouted.get());
            return Arrays.asList(null, "cnx_a");
        });
        when(tenantWorkScope.withCatalog(nullable(String.class), any())).thenAnswer(invocation -> {
            String catalog = invocation.getArgument(0);
            if (catalog == null) {
                defaultPinned.set(true);
            } else {
                pinnedCatalog.set(catalog);
            }
            try {
                return invocation.<Supplier<?>>getArgument(1).get();
            } finally {
                defaultPinned.set(false);
                pinnedCatalog.set(null);
            }
        });
        when(ruleMapper.workspaceIdsWithRules())
            .thenAnswer(invocation -> {
                assertTrue(defaultPinned.get());
                assertNull(pinnedCatalog.get());
                return List.of();
            })
            .thenAnswer(invocation -> {
                assertEquals("cnx_a", pinnedCatalog.get());
                return List.of(3, 7);
            });
        when(tenantWorkScope.withWorkspacePlacement(eq(3), any())).thenAnswer(invocation ->
            invocation.<BiFunction<Integer, String, ?>>getArgument(1).apply(30, "cnx_a"));
        when(tenantWorkScope.withWorkspacePlacement(eq(7), any())).thenAnswer(invocation ->
            invocation.<BiFunction<Integer, String, ?>>getArgument(1).apply(70, "cnx_a"));
        doAnswer(invocation -> {
            assertNull(pinnedCatalog.get());
            return null;
        }).when(backfillTransaction).backfillWorkspace(any(), anyInt());

        runner.run(arguments);

        InOrder order = inOrder(tenantWorkScope, placementRegistry, ruleMapper, backfillTransaction);
        order.verify(tenantWorkScope).unrouted(any());
        order.verify(placementRegistry).activeCatalogs();
        order.verify(tenantWorkScope).withCatalog(isNull(), any());
        order.verify(ruleMapper).workspaceIdsWithRules();
        order.verify(tenantWorkScope).withCatalog(eq("cnx_a"), any());
        order.verify(ruleMapper).workspaceIdsWithRules();
        order.verify(tenantWorkScope).withWorkspacePlacement(eq(3), any());
        order.verify(backfillTransaction).backfillWorkspace("cnx_a", 3);
        order.verify(tenantWorkScope).withWorkspacePlacement(eq(7), any());
        order.verify(backfillTransaction).backfillWorkspace("cnx_a", 7);
    }

    @Test
    void staleCatalogRowsFailBeforeAnyMutation() {
        when(tenantWorkScope.unrouted(any())).thenReturn(List.of("cnx_a"));
        when(tenantWorkScope.withCatalog(eq("cnx_a"), any())).thenReturn(List.of(3));
        when(tenantWorkScope.withWorkspacePlacement(eq(3), any())).thenAnswer(invocation ->
            invocation.<BiFunction<Integer, String, ?>>getArgument(1).apply(30, "cnx_b"));

        assertThrows(IllegalStateException.class, () -> runner.run(arguments));

        verify(backfillTransaction, never()).backfillWorkspace(any(), anyInt());
    }

    @Test
    void unservableWorkspaceIsSkippedWithoutStarvingTheRemainingCatalogSweep() {
        when(tenantWorkScope.unrouted(any())).thenReturn(Arrays.asList((String) null));
        when(tenantWorkScope.withCatalog(isNull(), any())).thenReturn(List.of(3, 7));
        when(tenantWorkScope.withWorkspacePlacement(eq(3), any()))
            .thenThrow(new ServiceUnavailableException("Placement is not servable"));
        when(tenantWorkScope.withWorkspacePlacement(eq(7), any())).thenAnswer(invocation ->
            invocation.<BiFunction<Integer, String, ?>>getArgument(1).apply(70, null));

        runner.run(arguments);

        verify(backfillTransaction, never()).backfillWorkspace(nullable(String.class), eq(3));
        verify(backfillTransaction).backfillWorkspace(null, 7);
    }

    @Test
    void oneStaleTransactionalSnapshotIsRetriedOnce() {
        when(tenantWorkScope.unrouted(any())).thenReturn(Arrays.asList((String) null));
        when(tenantWorkScope.withCatalog(isNull(), any())).thenReturn(List.of(7));
        when(tenantWorkScope.withWorkspacePlacement(eq(7), any())).thenAnswer(invocation ->
            invocation.<BiFunction<Integer, String, ?>>getArgument(1).apply(70, null));
        doThrow(new IllegalStateException("stale"))
            .doNothing()
            .when(backfillTransaction).backfillWorkspace(null, 7);

        runner.run(arguments);

        verify(backfillTransaction, times(2)).backfillWorkspace(null, 7);
    }

    @Test
    void emptyCatalogListPerformsNoTenantMapperWork() {
        when(tenantWorkScope.unrouted(any())).thenReturn(List.of());

        runner.run(arguments);

        verify(ruleMapper, never()).workspaceIdsWithRules();
        verify(backfillTransaction, never()).backfillWorkspace(any(), anyInt());
    }

    @Test
    void isRestrictedToNormalMaintenanceMode() {
        ConditionalOnProperty condition = LegacyWorkflowBackfillRunner.class
            .getAnnotation(ConditionalOnProperty.class);

        assertEquals("connex.maintenance", condition.prefix());
        assertArrayEquals(new String[] {"mode"}, condition.name());
        assertEquals("off", condition.havingValue());
        assertTrue(condition.matchIfMissing());
    }
}
