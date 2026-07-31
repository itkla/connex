package ooo.klae.connex.backend.services;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.exceptions.ConflictException;

/**
 * Applies the historical-import notification suppression protocol to provider backfill.
 */
@Service
@RequiredArgsConstructor
public class ProviderCaptureHistoricalBaselineService {
    private final NotificationReconciliationService notificationReconciliationService;

    /** Captures notification expectations before a historical provider page is projected. */
    public Snapshot snapshot(int workspaceId, Instant evaluationInstant) {
        return snapshot(
            workspaceId, evaluationInstant, Set.of(), Set.of());
    }

    /** Captures actual and provider-free expectations before a historical mutation. */
    public Snapshot snapshot(
            int workspaceId,
            Instant evaluationInstant,
            Set<Integer> personIds,
            Set<Integer> activityIds) {
        NotificationReconciliationService.HistoricalBaselineScope scope =
            scope(personIds, activityIds);
        return new Snapshot(
            notificationReconciliationService.historicalExpectationSnapshot(
                workspaceId, evaluationInstant),
            notificationReconciliationService.historicalExpectationSnapshot(
                workspaceId, evaluationInstant, scope));
    }

    /** Persists baselines for expectations changed by historical provider evidence. */
    public void persist(
            int workspaceId,
            Instant evaluationInstant,
            Snapshot before,
            Set<Integer> personIds,
            Set<Integer> activityIds,
            String importRunId) {
        if (personIds.isEmpty()) {
            return;
        }
        NotificationReconciliationService.HistoricalBaselineScope scope =
            scope(personIds, activityIds);
        NotificationReconciliationService.HistoricalExpectationSnapshot after =
            notificationReconciliationService.historicalExpectationSnapshot(
                workspaceId, evaluationInstant);
        NotificationReconciliationService.HistoricalExpectationSnapshot counterfactual =
            notificationReconciliationService.historicalExpectationSnapshot(
                workspaceId, evaluationInstant, scope);
        if (!scope.sameRelevantExpectations(
                before.counterfactual, counterfactual)) {
            throw new ConflictException(
                "Notification inputs changed during provider backfill");
        }
        notificationReconciliationService.persistHistoricalBaselines(
            workspaceId,
            Objects.requireNonNull(before.actual),
            after,
            scope,
            importRunId);
    }

    private static NotificationReconciliationService.HistoricalBaselineScope scope(
            Set<Integer> personIds, Set<Integer> activityIds) {
        return new NotificationReconciliationService.HistoricalBaselineScope(
            personIds, activityIds, Set.of(), Set.of());
    }

    /** Opaque before-state carried across one provider mutation transaction. */
    public static final class Snapshot {
        private final NotificationReconciliationService.HistoricalExpectationSnapshot actual;
        private final NotificationReconciliationService.HistoricalExpectationSnapshot
            counterfactual;

        Snapshot(
                NotificationReconciliationService.HistoricalExpectationSnapshot actual,
                NotificationReconciliationService.HistoricalExpectationSnapshot
                    counterfactual) {
            this.actual = actual;
            this.counterfactual = counterfactual;
        }
    }
}
