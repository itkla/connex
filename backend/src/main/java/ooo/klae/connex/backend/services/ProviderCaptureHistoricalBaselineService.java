package ooo.klae.connex.backend.services;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess.RecordVisibilityScope;

/**
 * Applies the historical-import notification suppression protocol to provider backfill.
 */
@Service
@RequiredArgsConstructor
public class ProviderCaptureHistoricalBaselineService {
    private final NotificationReconciliationService notificationReconciliationService;

    /**
     * Captures actual and provider-free expectations using the record-visibility scope resolved
     * before the historical writer acquired any locks.
     */
    public Snapshot snapshot(
            int workspaceId,
            Instant evaluationInstant,
            Set<Integer> personIds,
            Set<Integer> activityIds,
            RecordVisibilityScope recordVisibilityScope) {
        NotificationReconciliationService.HistoricalBaselineScope scope =
            scope(personIds, activityIds);
        return new Snapshot(
            notificationReconciliationService.historicalExpectationSnapshot(
                workspaceId, evaluationInstant, recordVisibilityScope),
            notificationReconciliationService.historicalExpectationSnapshot(
                workspaceId, evaluationInstant, scope, recordVisibilityScope),
            recordVisibilityScope);
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
                workspaceId, evaluationInstant, before.recordVisibilityScope);
        NotificationReconciliationService.HistoricalExpectationSnapshot counterfactual =
            notificationReconciliationService.historicalExpectationSnapshot(
                workspaceId, evaluationInstant, scope, before.recordVisibilityScope);
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
        private final RecordVisibilityScope recordVisibilityScope;

        Snapshot(
                NotificationReconciliationService.HistoricalExpectationSnapshot actual,
                NotificationReconciliationService.HistoricalExpectationSnapshot
                    counterfactual,
                RecordVisibilityScope recordVisibilityScope) {
            this.actual = actual;
            this.counterfactual = counterfactual;
            this.recordVisibilityScope = Objects.requireNonNull(recordVisibilityScope);
        }
    }
}
