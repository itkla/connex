package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.services.RelationshipSignalDetectorService.Detection;

/** Coordinates independent deterministic detectors without collapsing partial failure into emptiness. */
@Service
@RequiredArgsConstructor
public class RelationshipSignalReconciliationService {
    private static final Logger log =
        LoggerFactory.getLogger(RelationshipSignalReconciliationService.class);

    private final RelationshipSignalDetectorService detectorService;
    private final RelationshipSignalWriteService writeService;
    private final Clock clock;

    /** Reconciles all three families for one explicitly routed workspace. */
    public Result reconcileWorkspace(int workspaceId) {
        Instant attemptedInstant = clock.instant();
        LocalDateTime attemptedAt = LocalDateTime.ofInstant(attemptedInstant, ZoneOffset.UTC);
        Set<String> successful = new LinkedHashSet<>();
        int failedCount = 0;
        failedCount += reconcile(
            workspaceId,
            RelationshipSignalDetectorService.RELATIONSHIP_DECAY,
            attemptedAt,
            () -> detectorService.detectDecay(workspaceId, UUID.randomUUID().toString()),
            successful);
        failedCount += reconcile(
            workspaceId,
            RelationshipSignalDetectorService.DEAL_RISK,
            attemptedAt,
            () -> detectorService.detectDealRisk(workspaceId, UUID.randomUUID().toString()),
            successful);
        failedCount += reconcile(
            workspaceId,
            RelationshipSignalDetectorService.WARM_PATH,
            attemptedAt,
            () -> detectorService.detectWarmPaths(
                workspaceId, UUID.randomUUID().toString(), attemptedInstant),
            successful);
        writeService.enforceWorkspaceCap(workspaceId, attemptedAt);
        return new Result(Set.copyOf(successful), failedCount);
    }

    private int reconcile(
            int workspaceId,
            String family,
            LocalDateTime attemptedAt,
            Detector detector,
            Set<String> successful) {
        try {
            Detection detection = detector.detect();
            writeService.replaceFamily(
                workspaceId,
                family,
                detection.generationToken(),
                detection.candidates(),
                attemptedAt,
                LocalDateTime.ofInstant(detection.evidenceAsOf(), ZoneOffset.UTC));
            successful.add(family);
            return 0;
        } catch (RuntimeException exception) {
            writeService.markUnavailable(
                workspaceId, family, attemptedAt, "detector_failed");
            log.warn(
                "Relationship signal detector failed workspace={} family={} exceptionClass={}",
                workspaceId, family, exception.getClass().getSimpleName());
            return 1;
        }
    }

    @FunctionalInterface
    private interface Detector {
        Detection detect();
    }

    /** Bounded reconciliation outcome for scheduling diagnostics. */
    public record Result(Set<String> successfulFamilies, int failedCount) {
        public Result {
            successfulFamilies = Set.copyOf(successfulFamilies);
        }
    }
}
