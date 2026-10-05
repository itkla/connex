package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.ConflictException;

class ProviderCaptureHistoricalBaselineServiceTest {

    @Test
    void updateUsesActualBeforeStateAndProviderFreeConcurrencyFence() {
        NotificationReconciliationService reconciliation =
            mock(NotificationReconciliationService.class);
        ProviderCaptureHistoricalBaselineService service =
            new ProviderCaptureHistoricalBaselineService(reconciliation);
        Instant at = Instant.parse("2026-07-30T09:00:00Z");
        NotificationReconciliationService.HistoricalExpectationSnapshot
            actualBefore = snapshot("actual-before");
        NotificationReconciliationService.HistoricalExpectationSnapshot
            counterfactualBefore = snapshot("provider-free");
        NotificationReconciliationService.HistoricalExpectationSnapshot
            actualAfter = snapshot("actual-after");
        NotificationReconciliationService.HistoricalExpectationSnapshot
            counterfactualAfter = snapshot("provider-free");
        when(reconciliation.historicalExpectationSnapshot(7, at))
            .thenReturn(actualBefore, actualAfter);
        when(reconciliation.historicalExpectationSnapshot(
                eq(7),
                eq(at),
                any(NotificationReconciliationService.HistoricalBaselineScope.class)))
            .thenReturn(counterfactualBefore, counterfactualAfter);

        ProviderCaptureHistoricalBaselineService.Snapshot before =
            service.snapshot(7, at, Set.of(44), Set.of(101));
        service.persist(
            7,
            at,
            before,
            Set.of(44),
            Set.of(202),
            "capture-run");

        verify(reconciliation).persistHistoricalBaselines(
            eq(7),
            same(actualBefore),
            same(actualAfter),
            eq(scope(Set.of(44), Set.of(202))),
            eq("capture-run"));
        verify(reconciliation).historicalExpectationSnapshot(7, at, scope(Set.of(44), Set.of(101)));
        verify(reconciliation).historicalExpectationSnapshot(7, at, scope(Set.of(44), Set.of(202)));
    }

    @Test
    void changedRelevantCounterfactualRefusesBaselinePersistence() {
        NotificationReconciliationService reconciliation = mock(NotificationReconciliationService.class);
        ProviderCaptureHistoricalBaselineService service =
            new ProviderCaptureHistoricalBaselineService(reconciliation);
        Instant at = Instant.parse("2026-07-30T09:00:00Z");
        when(reconciliation.historicalExpectationSnapshot(7, at))
            .thenReturn(snapshot("actual-before"), snapshot("actual-after"));
        when(reconciliation.historicalExpectationSnapshot(7, at, scope(Set.of(44), Set.of(101))))
            .thenReturn(snapshot("provider-free-before"));
        when(reconciliation.historicalExpectationSnapshot(7, at, scope(Set.of(44), Set.of(202))))
            .thenReturn(snapshot("provider-free-changed"));
        ProviderCaptureHistoricalBaselineService.Snapshot before =
            service.snapshot(7, at, Set.of(44), Set.of(101));

        assertThrows(ConflictException.class,
            () -> service.persist(7, at, before, Set.of(44), Set.of(202), "capture-run"));

        verify(reconciliation, never()).persistHistoricalBaselines(
            eq(7), any(), any(), any(), any());
    }

    private static NotificationReconciliationService.HistoricalBaselineScope scope(
            Set<Integer> personIds, Set<Integer> activityIds) {
        return new NotificationReconciliationService.HistoricalBaselineScope(
            personIds, activityIds, Set.of(), Set.of());
    }

    private static NotificationReconciliationService.HistoricalExpectationSnapshot
            snapshot(String sourceStateHash) {
        return new NotificationReconciliationService.HistoricalExpectationSnapshot(
            Map.of(new NotificationReconciliationService.HistoricalExpectationKey(7, 8, "relationship.cooling:7:44"),
                new NotificationReconciliationService.HistoricalExpectation(
                    "relationship.cooling", "warning", sourceStateHash)));
    }
}
