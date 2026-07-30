package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;

import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.IdentityBackfillTransaction.IdentityBackfillBatch;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Unit coverage for catalog-safe canonical identity backfill orchestration.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class IdentityBackfillRunnerTest {

    @Mock private PlacementRegistry placementRegistry;
    @Mock private TenantWorkScope tenantWorkScope;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private IdentityBackfillTransaction backfillTransaction;
    @Mock private ApplicationArguments arguments;

    private IdentityBackfillRunner runner;

    @BeforeEach
    void setUp() {
        runner = new IdentityBackfillRunner(
            placementRegistry,
            tenantWorkScope,
            workspaceMapper,
            backfillTransaction);
    }

    @Test
    void isRestrictedToNormalMaintenanceMode() {
        ConditionalOnProperty condition = IdentityBackfillRunner.class
            .getAnnotation(ConditionalOnProperty.class);

        assertEquals("connex.maintenance", condition.prefix());
        assertArrayEquals(new String[] {"mode"}, condition.name());
        assertEquals("off", condition.havingValue());
        assertTrue(condition.matchIfMissing());
    }

    @Test
    void enumeratesControlPlaneStateAndProcessesSortedWorkspaceGroups() {
        AtomicBoolean unrouted = new AtomicBoolean();
        AtomicInteger unroutedCalls = new AtomicInteger();
        when(tenantWorkScope.unrouted(any())).thenAnswer(invocation -> {
            unrouted.set(true);
            try {
                int call = unroutedCalls.getAndIncrement();
                if (call == 0) {
                    invocation.<Supplier<?>>getArgument(0).get();
                    return Arrays.asList(null, "cnx_a");
                }
                invocation.<Supplier<?>>getArgument(0).get();
                return List.of(9, 3, 7);
            } finally {
                unrouted.set(false);
            }
        });
        when(placementRegistry.activeCatalogs()).thenAnswer(invocation -> {
            assertTrue(unrouted.get());
            return Arrays.asList(null, "cnx_a");
        });
        when(workspaceMapper.findWorkspaceIds()).thenAnswer(invocation -> {
            assertTrue(unrouted.get());
            return List.of(9, 3, 7);
        });
        serveWorkspace(9, "cnx_a");
        serveWorkspace(3, "cnx_a");
        serveWorkspace(7, null);
        stubEmptyPages();

        runner.run(arguments);

        InOrder order = inOrder(backfillTransaction);
        order.verify(backfillTransaction).backfillPersonPage(null, 7, 0, 500);
        order.verify(backfillTransaction).backfillCompanyPage(null, 7, 0, 500);
        order.verify(backfillTransaction).rebuildCollisionReport(null, 7);
        order.verify(backfillTransaction).backfillPersonPage("cnx_a", 3, 0, 500);
        order.verify(backfillTransaction).backfillCompanyPage("cnx_a", 3, 0, 500);
        order.verify(backfillTransaction).rebuildCollisionReport("cnx_a", 3);
        order.verify(backfillTransaction).backfillPersonPage("cnx_a", 9, 0, 500);
        order.verify(backfillTransaction).backfillCompanyPage("cnx_a", 9, 0, 500);
        order.verify(backfillTransaction).rebuildCollisionReport("cnx_a", 9);
    }

    @Test
    void placementMismatchOnSecondResolutionFailsBeforeMutation() {
        stubEnumeration(List.of("cnx_a"), List.of(3));
        AtomicInteger resolutions = new AtomicInteger();
        when(tenantWorkScope.withWorkspacePlacement(eq(3), any())).thenAnswer(invocation -> {
            String catalog = resolutions.getAndIncrement() == 0 ? "cnx_a" : "cnx_b";
            return invocation.<BiFunction<Integer, String, ?>>getArgument(1).apply(30, catalog);
        });

        assertThrows(IllegalStateException.class, () -> runner.run(arguments));

        verify(backfillTransaction, never()).backfillPersonPage(anyString(), anyInt(), anyInt(), anyInt());
        verify(backfillTransaction, never()).backfillCompanyPage(anyString(), anyInt(), anyInt(), anyInt());
        verify(backfillTransaction, never()).rebuildCollisionReport(anyString(), anyInt());
    }

    @Test
    void unservableWorkspaceIsWarnSkippedWithoutStarvingLaterWork(CapturedOutput output) {
        stubEnumeration(Arrays.asList((String) null), List.of(3, 7));
        AtomicInteger firstWorkspaceResolutions = new AtomicInteger();
        when(tenantWorkScope.withWorkspacePlacement(eq(3), any())).thenAnswer(invocation -> {
            if (firstWorkspaceResolutions.getAndIncrement() == 0) {
                return invocation.<BiFunction<Integer, String, ?>>getArgument(1).apply(30, null);
            }
            throw new ServiceUnavailableException("Placement is not servable");
        });
        serveWorkspace(7, null);
        stubEmptyPages();

        runner.run(arguments);

        verify(backfillTransaction, never()).backfillPersonPage(isNull(), eq(3), anyInt(), anyInt());
        verify(backfillTransaction).backfillPersonPage(null, 7, 0, 500);
        assertTrue(output.getOut().contains("skipped unservable workspace 3"));
    }

    @Test
    void unexpectedPlacementFailureRemainsFatal() {
        stubEnumeration(Arrays.asList((String) null), List.of(3));
        when(tenantWorkScope.withWorkspacePlacement(eq(3), any()))
            .thenThrow(new IllegalArgumentException("corrupt placement"));

        assertThrows(IllegalArgumentException.class, () -> runner.run(arguments));

        verify(backfillTransaction, never()).backfillPersonPage(
            nullable(String.class), anyInt(), anyInt(), anyInt());
    }

    @Test
    void failedWorkspaceSweepIsLoggedWithoutStoppingStartupOrLaterWork(CapturedOutput output) {
        stubEnumeration(Arrays.asList((String) null), List.of(3, 7));
        serveWorkspace(3, null);
        serveWorkspace(7, null);
        when(backfillTransaction.backfillPersonPage(null, 3, 0, 500))
            .thenThrow(new NullPointerException("instant"));
        when(backfillTransaction.backfillPersonPage(null, 7, 0, 500))
            .thenReturn(new IdentityBackfillBatch(0, 0, 0, 0, 0, 0, 0, 0));
        when(backfillTransaction.backfillCompanyPage(null, 7, 0, 500))
            .thenReturn(new IdentityBackfillBatch(0, 0, 0, 0, 0, 0, 0, 0));
        when(backfillTransaction.rebuildCollisionReport(null, 7)).thenReturn(0);

        runner.run(arguments);

        verify(backfillTransaction, never()).rebuildCollisionReport(isNull(), eq(3));
        verify(backfillTransaction).backfillPersonPage(null, 7, 0, 500);
        assertTrue(output.getOut().contains(
            "Canonical identity backfill failed for workspace 3"));
    }

    @Test
    void pageCursorAdvancesWhenEveryValueIsInvalid() {
        stubEnumeration(Arrays.asList((String) null), List.of(7));
        serveWorkspace(7, null);
        IdentityBackfillBatch invalidPage =
            new IdentityBackfillBatch(750, 500, 0, 0, 500, 500, 0, 0);
        IdentityBackfillBatch emptyPerson =
            new IdentityBackfillBatch(750, 0, 0, 0, 0, 0, 0, 0);
        IdentityBackfillBatch emptyCompany =
            new IdentityBackfillBatch(0, 0, 0, 0, 0, 0, 0, 0);
        when(backfillTransaction.backfillPersonPage(null, 7, 0, 500)).thenReturn(invalidPage);
        when(backfillTransaction.backfillPersonPage(null, 7, 750, 500)).thenReturn(emptyPerson);
        when(backfillTransaction.backfillCompanyPage(null, 7, 0, 500)).thenReturn(emptyCompany);
        when(backfillTransaction.rebuildCollisionReport(null, 7)).thenReturn(0);

        runner.run(arguments);

        InOrder order = inOrder(backfillTransaction);
        order.verify(backfillTransaction).backfillPersonPage(null, 7, 0, 500);
        order.verify(backfillTransaction).backfillPersonPage(null, 7, 750, 500);
        order.verify(backfillTransaction).backfillCompanyPage(null, 7, 0, 500);
        order.verify(backfillTransaction).rebuildCollisionReport(null, 7);
    }

    @Test
    void completionLogContainsCountsWithoutIdentityValues(CapturedOutput output) {
        stubEnumeration(Arrays.asList((String) null), List.of(7));
        serveWorkspace(7, null);
        when(backfillTransaction.backfillPersonPage(null, 7, 0, 500))
            .thenReturn(new IdentityBackfillBatch(1, 1, 1, 0, 0, 0, 0, 0));
        when(backfillTransaction.backfillCompanyPage(null, 7, 0, 500))
            .thenReturn(new IdentityBackfillBatch(0, 0, 0, 0, 0, 0, 0, 0));
        when(backfillTransaction.rebuildCollisionReport(null, 7)).thenReturn(2);

        runner.run(arguments);

        assertTrue(output.getOut().contains(
            "workspace 7: scanned=1, created=1, existing=0, invalidEmail=0"));
        assertTrue(!output.getOut().contains("@") && !output.getOut().contains("+8190"));
    }

    @Test
    void contendedCollisionRebuildIsRetriedWithinTheSameWorkspace() {
        stubEnumeration(Arrays.asList((String) null), List.of(7));
        serveWorkspace(7, null);
        stubEmptyBackfillPages(7);
        when(backfillTransaction.rebuildCollisionReport(null, 7))
            .thenThrow(
                new DuplicateKeyException("collision membership already rebuilt"),
                new ConcurrencyFailureException("deadlock found when trying to get lock"))
            .thenReturn(4);

        runner.run(arguments);

        verify(backfillTransaction, times(3)).rebuildCollisionReport(null, 7);
    }

    @Test
    void exhaustedCollisionRebuildRetriesAreLoggedWithoutAbortingStartup(
            CapturedOutput output) {
        stubEnumeration(Arrays.asList((String) null), List.of(7));
        serveWorkspace(7, null);
        stubEmptyBackfillPages(7);
        DuplicateKeyException first = new DuplicateKeyException("first");
        ConcurrencyFailureException second = new ConcurrencyFailureException("second");
        DuplicateKeyException last = new DuplicateKeyException("last");
        when(backfillTransaction.rebuildCollisionReport(null, 7)).thenThrow(first, second, last);

        runner.run(arguments);

        assertArrayEquals(new Throwable[] {first, second}, last.getSuppressed());
        verify(backfillTransaction, times(3)).rebuildCollisionReport(null, 7);
        assertTrue(output.getOut().contains(
            "Canonical identity backfill failed for workspace 7"));
    }

    private void stubEmptyBackfillPages(int workspaceId) {
        when(backfillTransaction.backfillPersonPage(null, workspaceId, 0, 500))
            .thenReturn(new IdentityBackfillBatch(0, 0, 0, 0, 0, 0, 0, 0));
        when(backfillTransaction.backfillCompanyPage(null, workspaceId, 0, 500))
            .thenReturn(new IdentityBackfillBatch(0, 0, 0, 0, 0, 0, 0, 0));
    }

    private void stubEnumeration(List<String> catalogs, List<Integer> workspaceIds) {
        AtomicInteger call = new AtomicInteger();
        when(tenantWorkScope.unrouted(any())).thenAnswer(invocation ->
            call.getAndIncrement() == 0 ? catalogs : workspaceIds);
    }

    private void serveWorkspace(int workspaceId, String catalog) {
        when(tenantWorkScope.withWorkspacePlacement(eq(workspaceId), any())).thenAnswer(invocation ->
            invocation.<BiFunction<Integer, String, ?>>getArgument(1)
                .apply(workspaceId * 10, catalog));
    }

    private void stubEmptyPages() {
        when(backfillTransaction.backfillPersonPage(
                nullable(String.class), anyInt(), eq(0), eq(500)))
            .thenAnswer(invocation ->
                new IdentityBackfillBatch(
                    invocation.getArgument(2), 0, 0, 0, 0, 0, 0, 0));
        when(backfillTransaction.backfillCompanyPage(
                nullable(String.class), anyInt(), eq(0), eq(500)))
            .thenAnswer(invocation ->
                new IdentityBackfillBatch(
                    invocation.getArgument(2), 0, 0, 0, 0, 0, 0, 0));
        when(backfillTransaction.rebuildCollisionReport(nullable(String.class), anyInt())).thenReturn(0);
    }
}
