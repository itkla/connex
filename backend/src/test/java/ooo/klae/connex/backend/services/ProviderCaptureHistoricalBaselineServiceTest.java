package ooo.klae.connex.backend.services;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ProviderCaptureHistoricalBaselineServiceTest {

    @Test
    void updateUsesActualBeforeStateAndProviderFreeConcurrencyFence() {
        NotificationReconciliationService reconciliation =
            mock(NotificationReconciliationService.class);
        ProviderCaptureHistoricalBaselineService service =
            new ProviderCaptureHistoricalBaselineService(reconciliation);
        Instant at = Instant.parse("2026-07-30T09:00:00Z");
        NotificationReconciliationService.HistoricalExpectationSnapshot
            actualBefore = snapshot();
        NotificationReconciliationService.HistoricalExpectationSnapshot
            counterfactualBefore = snapshot();
        NotificationReconciliationService.HistoricalExpectationSnapshot
            actualAfter = snapshot();
        NotificationReconciliationService.HistoricalExpectationSnapshot
            counterfactualAfter = snapshot();
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
            eq(actualBefore),
            eq(actualAfter),
            any(NotificationReconciliationService.HistoricalBaselineScope.class),
            eq("capture-run"));
    }

    private static NotificationReconciliationService.HistoricalExpectationSnapshot
            snapshot() {
        return new NotificationReconciliationService.HistoricalExpectationSnapshot(
            Map.of());
    }
}
