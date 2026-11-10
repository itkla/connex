package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.RelationshipSignal;
import ooo.klae.connex.backend.services.RelationshipSignalDetectorService.Detection;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;

/** Coordinates independent deterministic detectors without collapsing partial failure into emptiness. */
@Service
@RequiredArgsConstructor
public class RelationshipSignalReconciliationService {
    private static final Logger log =
        LoggerFactory.getLogger(RelationshipSignalReconciliationService.class);

    private final RelationshipSignalDetectorService detectorService;
    private final RelationshipSignalWriteService writeService;
    private final Clock clock;

    private final RecordVisibilityScopeControlAccess recordVisibilityScopeControlAccess;

    /** Reconciles all three families for one explicitly routed workspace. */
    public Result reconcileWorkspace(int workspaceId) {
        return recordVisibilityScopeControlAccess.withDeferredWorkspaceScope(
            workspaceId, () -> reconcileWorkspaceWithinVisibilityScope(workspaceId));
    }

    private Result reconcileWorkspaceWithinVisibilityScope(int workspaceId) {
        Instant attemptedInstant = clock.instant();
        LocalDateTime attemptedAt = LocalDateTime.ofInstant(attemptedInstant, ZoneOffset.UTC);
        List<ComputedFamily> computed = new ArrayList<>();
        int failedCount = detect(
            workspaceId, RelationshipSignalDetectorService.RELATIONSHIP_DECAY, attemptedAt,
            () -> detectorService.detectDecay(workspaceId, UUID.randomUUID().toString()), computed);
        failedCount += detect(
            workspaceId, RelationshipSignalDetectorService.DEAL_RISK, attemptedAt,
            () -> detectorService.detectDealRisk(workspaceId, UUID.randomUUID().toString()), computed);
        failedCount += detect(
            workspaceId, RelationshipSignalDetectorService.WARM_PATH, attemptedAt,
            () -> detectorService.detectWarmPaths(workspaceId, UUID.randomUUID().toString(), attemptedInstant), computed);
        Set<String> computedNames = computed.stream().map(ComputedFamily::family)
            .collect(java.util.stream.Collectors.toSet());
        Map<String, RelationshipSignal> candidates = new LinkedHashMap<>();
        for (RelationshipSignal retained : writeService.activeCandidates(workspaceId)) {
            if (!computedNames.contains(retained.getFamily())) candidates.put(retained.getDedupeKey(), retained);
        }
        for (ComputedFamily family : computed) {
            for (RelationshipSignal candidate : family.detection().candidates()) {
                candidates.put(candidate.getDedupeKey(), candidate);
            }
        }
        Set<String> admitted = candidates.values().stream()
            .sorted(Comparator.comparingInt(RelationshipSignal::getPriorityRank)
                .thenComparing(Comparator.comparingInt(RelationshipSignal::getRankValue).reversed())
                .thenComparing(RelationshipSignal::getFamily)
                .thenComparing(RelationshipSignal::getSubjectType)
                .thenComparingInt(RelationshipSignal::getSubjectId))
            .limit(RelationshipSignalWriteService.WORKSPACE_CAP)
            .map(RelationshipSignal::getDedupeKey).collect(java.util.stream.Collectors.toSet());
        Set<String> successful = new LinkedHashSet<>();
        int changedCount = 0;
        for (ComputedFamily family : computed) {
            try {
                Detection detection = family.detection();
                changedCount += writeService.replaceFamily(
                    workspaceId, family.family(), detection.generationToken(),
                    detection.candidates().stream().filter(candidate -> admitted.contains(candidate.getDedupeKey())).toList(),
                    attemptedAt, LocalDateTime.ofInstant(detection.evidenceAsOf(), ZoneOffset.UTC));
                successful.add(family.family());
            } catch (TenantScopeSnapshotException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                unavailable(workspaceId, family.family(), attemptedAt, exception);
                failedCount++;
            }
        }
        changedCount += writeService.enforceWorkspaceCap(workspaceId, attemptedAt);
        return new Result(Set.copyOf(successful), failedCount, changedCount);
    }

    private int detect(int workspaceId, String family, LocalDateTime attemptedAt,
            Detector detector, List<ComputedFamily> computed) {
        try {
            Detection detection = detector.detect();
            if (detection == null) throw new IllegalStateException("Signal detection is missing");
            computed.add(new ComputedFamily(family, detection));
            return 0;
        } catch (TenantScopeSnapshotException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            unavailable(workspaceId, family, attemptedAt, exception);
            return 1;
        }
    }

    private void unavailable(int workspaceId, String family, LocalDateTime attemptedAt, RuntimeException exception) {
        writeService.markUnavailable(workspaceId, family, attemptedAt, "detector_failed");
        log.warn("Relationship signal detector failed workspace={} family={} exceptionClass={}",
            workspaceId, family, exception.getClass().getSimpleName());
    }

    private record ComputedFamily(String family, Detection detection) {}

    @FunctionalInterface
    private interface Detector {
        Detection detect();
    }

    /** Bounded reconciliation outcome for scheduling diagnostics. */
    public record Result(Set<String> successfulFamilies, int failedCount, int changedCount) {
        /** Retains legacy construction for callers without change telemetry. */
        public Result(Set<String> successfulFamilies, int failedCount) {
            this(successfulFamilies, failedCount, 1);
        }

        public Result {
            successfulFamilies = Set.copyOf(successfulFamilies);
        }
    }
}
