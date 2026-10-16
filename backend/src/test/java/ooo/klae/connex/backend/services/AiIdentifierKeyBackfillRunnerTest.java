package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import ooo.klae.connex.backend.beans.AiAssistantIdentifierMention;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.AiAssistantIdentifierMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AiIdentifierKeyBackfillTransaction.Batch;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class AiIdentifierKeyBackfillRunnerTest {
    @Mock private PlacementRegistry placements;
    @Mock private TenantWorkScope scope;
    @Mock private WorkspaceMapper workspaces;
    @Mock private AiIdentifierKeyBackfillTransaction transaction;
    @Mock private ApplicationArguments arguments;
    private AiIdentifierKeyBackfillRunner runner;

    @BeforeEach
    void setUp() {
        runner = new AiIdentifierKeyBackfillRunner(placements, scope, workspaces, transaction);
    }

    @Test
    void normalProfilesRunButMaintenanceAndSeederDoNot() {
        var condition = AiIdentifierKeyBackfillRunner.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("connex.maintenance", condition.prefix());
        assertArrayEquals(new String[] {"mode"}, condition.name());
        assertEquals("off", condition.havingValue());
        assertTrue(condition.matchIfMissing());
    }

    @Test
    void pinsCatalogsSortsWorkspacesAndRestartsFromZero() {
        enumeration(Arrays.asList(null, "cnx_a"), List.of(9, 7, 3));
        serve(9, "cnx_a");
        serve(3, "cnx_a");
        serve(7, null);
        when(transaction.backfillPage(anyInt(), anyString(), eq(0))).thenReturn(new Batch(0, 0, 0, 0));

        runner.run(arguments);
        var ordered = inOrder(transaction);
        for (int workspace : List.of(7, 3, 9)) {
            for (String kind : List.of("person", "company", "deal")) {
                ordered.verify(transaction).backfillPage(workspace, kind, 0);
            }
            ordered.verify(transaction).pendingCount(workspace);
        }
        runner.run(arguments);
        verify(transaction, org.mockito.Mockito.times(2)).backfillPage(7, "person", 0);
    }

    @Test
    void fullSkippedPagesAdvanceAndPendingCountReportsRaces(CapturedOutput output) {
        enumeration(Arrays.asList((String) null), List.of(7));
        serve(7, null);
        when(transaction.backfillPage(7, "person", 0)).thenReturn(new Batch(151, 100, 0, 100));
        when(transaction.backfillPage(7, "person", 151)).thenReturn(new Batch(152, 1, 1, 0));
        when(transaction.backfillPage(7, "company", 0)).thenReturn(new Batch(0, 0, 0, 0));
        when(transaction.backfillPage(7, "deal", 0)).thenReturn(new Batch(0, 0, 0, 0));
        when(transaction.pendingCount(7)).thenReturn(100L);

        runner.run(arguments);
        verify(transaction).backfillPage(7, "person", 151);
        assertTrue(output.getOut().contains("scanned=101, written=1, skipped=100, pending=100"));
    }

    @Test
    void placementChangeFailsBeforeAnyMutation() {
        enumeration(List.of("cnx_a"), List.of(7));
        AtomicInteger resolutions = new AtomicInteger();
        when(scope.withWorkspacePlacement(eq(7), any())).thenAnswer(call ->
                call.<BiFunction<Integer, String, ?>>getArgument(1)
                        .apply(70, resolutions.getAndIncrement() == 0 ? "cnx_a" : "cnx_b"));

        assertThrows(IllegalStateException.class, () -> runner.run(arguments));
        verify(transaction, never()).backfillPage(anyInt(), anyString(), anyInt());
    }

    @Test
    void unservableAndFailedWorkspacesDoNotStarveLaterGroups(CapturedOutput output) {
        enumeration(Arrays.asList((String) null), List.of(3, 7, 9));
        when(scope.withWorkspacePlacement(eq(3), any())).thenThrow(new ServiceUnavailableException("unservable"));
        serve(7, null);
        serve(9, null);
        when(transaction.backfillPage(7, "person", 0)).thenThrow(new IllegalStateException("sensitive name"));
        when(transaction.backfillPage(eq(9), anyString(), eq(0))).thenReturn(new Batch(0, 0, 0, 0));

        runner.run(arguments);
        verify(transaction).pendingCount(9);
        assertTrue(output.getOut().contains("skipped unservable workspace 3"));
        assertTrue(output.getOut().contains("failed for workspace 7"));
        assertTrue(!output.getOut().contains("sensitive name"));
    }

    @Test
    void transactionValidatesPagesAndCountsCompareAndSetSkips() {
        var mapper = mock(AiAssistantIdentifierMapper.class);
        var backfill = new AiIdentifierKeyBackfillTransaction(mapper);
        AiAssistantIdentifierMention row = new AiAssistantIdentifierMention();
        row.setKind("person");
        row.setId(11);
        row.setValue("㍿ Team");
        when(mapper.findPersonKeysToBackfill(7, 0, 100, 1)).thenReturn(List.of(row));
        when(mapper.updatePersonKeysIfNameCurrent(7, 11, "㍿ Team", 1)).thenReturn(0);

        assertEquals(new Batch(11, 1, 0, 1), backfill.backfillPage(7, "person", 0));
        assertThrows(IllegalArgumentException.class, () -> backfill.backfillPage(0, "person", 0));
        assertThrows(IllegalArgumentException.class, () -> backfill.backfillPage(7, "other", 0));
        row.setId(0);
        assertThrows(IllegalStateException.class, () -> backfill.backfillPage(7, "person", 0));
    }

    private void enumeration(List<String> catalogs, List<Integer> ids) {
        AtomicInteger calls = new AtomicInteger();
        when(scope.unroutedRead(any())).thenAnswer(call -> calls.getAndIncrement() % 2 == 0 ? catalogs : ids);
    }

    private void serve(int workspace, String catalog) {
        when(scope.withWorkspacePlacement(eq(workspace), any())).thenAnswer(call ->
                call.<BiFunction<Integer, String, ?>>getArgument(1).apply(workspace * 10, catalog));
    }
}
