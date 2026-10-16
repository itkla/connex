package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.Scheduled;

import ooo.klae.connex.backend.ai.masking.AiIdentifierLookupKeys;
import ooo.klae.connex.backend.beans.AiAssistantIdentifierMention;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.AiAssistantIdentifierMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AiIdentifierKeyBackfillTransaction.Batch;
import ooo.klae.connex.backend.tenant.ControlPlaneRead;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class AiIdentifierKeyBackfillRunnerTest {
    @Mock private PlacementRegistry placements;
    @Mock private TenantWorkScope scope;
    @Mock private WorkspaceMapper workspaces;
    @Mock private AiIdentifierKeyBackfillTransaction transaction;
    private AiIdentifierKeyBackfillRunner runner;

    @BeforeEach
    void setUp() {
        runner = new AiIdentifierKeyBackfillRunner(placements, scope, workspaces, transaction);
    }

    @Test
    void normalProfilesScheduleWithoutAStartupReadinessDependency() throws Exception {
        var condition = AiIdentifierKeyBackfillRunner.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("connex.maintenance", condition.prefix());
        assertArrayEquals(new String[] {"mode"}, condition.name());
        assertEquals("off", condition.havingValue());
        assertTrue(condition.matchIfMissing());
        assertFalse(ApplicationRunner.class.isAssignableFrom(AiIdentifierKeyBackfillRunner.class));
        Scheduled timer = AiIdentifierKeyBackfillRunner.class.getMethod("reconcile").getAnnotation(Scheduled.class);
        assertEquals("${connex.ai.identifier-backfill.delay-ms:30000}", timer.fixedDelayString());
    }

    @Test
    void eachInvocationIsBoundedAndResumesItsCommittedCursor(CapturedOutput output) {
        enumeration(List.of(7));
        serve(7, null);
        when(transaction.backfillPage(eq(7), eq("person"), anyInt())).thenAnswer(call ->
                new Batch((int) call.getArgument(2) + 100, 100, 99, 1));
        when(transaction.pendingCount(7)).thenReturn(100L);
        runner.reconcile();
        verify(transaction, times(4)).backfillPage(eq(7), eq("person"), anyInt());
        verify(transaction).backfillPage(7, "person", 300);
        runner.reconcile();
        verify(transaction, times(8)).backfillPage(eq(7), eq("person"), anyInt());
        verify(transaction).backfillPage(7, "person", 700);
        verify(scope, times(8)).withWorkspacePlacement(eq(7), any());
        assertTrue(output.getOut().contains("scanned=100, written=99, skipped=1, pending=100"));
        new AiIdentifierKeyBackfillRunner(placements, scope, workspaces, transaction).reconcile();
        verify(transaction, times(2)).backfillPage(7, "person", 0);
    }

    @Test
    void completedWorkspacesAdvanceAndACompletedTraversalWrapsForRaces() {
        enumeration(List.of(7, 9));
        serve(7, null);
        serve(9, "cnx_a");
        when(transaction.backfillPage(anyInt(), anyString(), eq(0))).thenReturn(new Batch(0, 0, 0, 0));
        runner.reconcile();
        verify(transaction).backfillPage(9, "person", 0);
        verify(transaction, never()).backfillPage(9, "company", 0);
        runner.reconcile();
        verify(transaction).backfillPage(9, "deal", 0);
        runner.reconcile();
        verify(transaction, times(2)).backfillPage(7, "person", 0);
    }

    @Test
    void changedPlacementDoesNotResumeAnotherCatalogsCursor(CapturedOutput output) {
        enumeration(List.of(7));
        AtomicInteger resolutions = new AtomicInteger();
        when(scope.withWorkspacePlacement(eq(7), any())).thenAnswer(call ->
                call.<BiFunction<Integer, String, ?>>getArgument(1)
                        .apply(70, resolutions.getAndIncrement() == 0 ? "cnx_a" : "cnx_b"));
        when(transaction.backfillPage(7, "person", 0)).thenReturn(new Batch(100, 100, 100, 0));
        runner.reconcile();
        verify(transaction, never()).backfillPage(7, "person", 100);
        assertTrue(output.getOut().contains("failed for workspace 7"));
    }

    @Test
    void failuresDoNotStarveLaterWorkspacesOrLogNames(CapturedOutput output) {
        enumeration(List.of(3, 7, 9));
        when(scope.withWorkspacePlacement(eq(3), any())).thenThrow(new ServiceUnavailableException("unservable"));
        serve(7, null);
        serve(9, null);
        when(transaction.backfillPage(7, "person", 0)).thenThrow(new IllegalStateException("sensitive name"));
        when(transaction.backfillPage(eq(9), anyString(), eq(0))).thenReturn(new Batch(0, 0, 0, 0));
        runner.reconcile();
        verify(transaction, times(2)).pendingCount(9);
        assertTrue(output.getOut().contains("failed for workspace 3"));
        assertTrue(output.getOut().contains("failed for workspace 7"));
        assertFalse(output.getOut().contains("sensitive name"));
    }

    @Test
    void transactionValidatesPagesAndCountsCompareAndSetSkips() {
        var mapper = mock(AiAssistantIdentifierMapper.class);
        var backfill = new AiIdentifierKeyBackfillTransaction(mapper);
        AiAssistantIdentifierMention row = new AiAssistantIdentifierMention();
        row.setKind("person");
        row.setId(11);
        row.setValue("㍿ Team");
        int version = AiIdentifierLookupKeys.VERSION;
        when(mapper.findPersonKeysToBackfill(7, 0, 100, version)).thenReturn(List.of(row));
        when(mapper.updatePersonKeysIfNameCurrent(7, 11, "㍿ Team", version)).thenReturn(0);
        assertEquals(new Batch(11, 1, 0, 1), backfill.backfillPage(7, "person", 0));
        assertThrows(IllegalArgumentException.class, () -> backfill.backfillPage(0, "person", 0));
        assertThrows(IllegalArgumentException.class, () -> backfill.backfillPage(7, "other", 0));
        row.setId(0);
        assertThrows(IllegalStateException.class, () -> backfill.backfillPage(7, "person", 0));
    }

    private void enumeration(List<Integer> ids) {
        when(scope.unroutedRead(any())).thenAnswer(call -> call.<ControlPlaneRead<?>>getArgument(0).read());
        when(placements.activeCatalogs()).thenReturn(Arrays.asList(null, "cnx_a", "cnx_b"));
        when(workspaces.findWorkspaceIdsPage(anyInt(), eq(1))).thenAnswer(call ->
                ids.stream().filter(id -> id > (int) call.getArgument(0)).limit(1).toList());
    }

    private void serve(int workspace, String catalog) {
        when(scope.withWorkspacePlacement(eq(workspace), any())).thenAnswer(call ->
                call.<BiFunction<Integer, String, ?>>getArgument(1).apply(workspace * 10, catalog));
    }
}
