package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.RelationshipSignal;
import ooo.klae.connex.backend.beans.RelationshipSignalFamilyState;
import ooo.klae.connex.backend.mappers.RelationshipSignalMapper;

/** Isolated tenant writes for detector reconciliation and bounded selection. */
@Service
@RequiredArgsConstructor
public class RelationshipSignalWriteService {
    static final int WORKSPACE_CAP = 50;

    private final RelationshipSignalMapper signalMapper;

    /**
     * Replaces a computed family and returns inserted, changed or resolved signal rows.
     * Unchanged duplicate matches can report one JDBC found row; they count as zero changes.
     */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED)
    public int replaceFamily(
            int workspaceId,
            String family,
            String generationToken,
            List<RelationshipSignal> candidates,
            LocalDateTime attemptedAt,
            LocalDateTime evidenceAsOf) {
        LocalDateTime normalizedAttemptedAt = attemptedAt.truncatedTo(ChronoUnit.MICROS);
        boolean familyStateCreated = signalMapper.ensureFamilyState(
            workspaceId, family, normalizedAttemptedAt) == 1;
        if (staleAttempt(
                workspaceId, family, normalizedAttemptedAt, familyStateCreated)) {
            return 0;
        }
        int changedCount = 0;
        for (RelationshipSignal candidate : candidates) {
            RelationshipSignal previous = signalMapper.lockByDedupeKey(workspaceId, candidate.getDedupeKey());
            int affected = signalMapper.upsertSignal(candidate);
            if (previous == null || affected > 1) {
                changedCount++;
            }
            if (previous != null && !candidate.getSourceModelVersion().equals(previous.getSourceModelVersion())) {
                signalMapper.rebaseModelState(workspaceId, previous.getId(), previous.getSourceStateHash(),
                    candidate.getSourceStateHash());
            }
        }
        changedCount += signalMapper.resolveMissingExcept(
            workspaceId, family, generationToken, normalizedAttemptedAt,
            candidates.stream().map(RelationshipSignal::getDedupeKey).toList());
        LocalDateTime oldestEvidence = evidenceAsOf;
        for (RelationshipSignal candidate : candidates) {
            LocalDateTime candidateEvidence = candidate.getEvidenceAsOf();
            if (candidateEvidence != null && (oldestEvidence == null || candidateEvidence.isBefore(oldestEvidence))) {
                oldestEvidence = candidateEvidence;
            }
        }
        signalMapper.upsertFamilyAvailable(
            workspaceId, family, normalizedAttemptedAt, oldestEvidence);
        return changedCount;
    }

    /** Records detector failure while preserving the family's last known signals and success time. */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED)
    public void markUnavailable(
            int workspaceId,
            String family,
            LocalDateTime attemptedAt,
            String errorCode) {
        LocalDateTime normalizedAttemptedAt = attemptedAt.truncatedTo(ChronoUnit.MICROS);
        boolean familyStateCreated = signalMapper.ensureFamilyState(
            workspaceId, family, normalizedAttemptedAt) == 1;
        if (staleAttempt(
                workspaceId, family, normalizedAttemptedAt, familyStateCreated)) {
            return;
        }
        signalMapper.upsertFamilyUnavailable(
            workspaceId, family, normalizedAttemptedAt, errorCode);
    }

    private boolean staleAttempt(
            int workspaceId,
            String family,
            LocalDateTime attemptedAt,
            boolean familyStateCreated) {
        RelationshipSignalFamilyState locked = signalMapper.lockFamilyState(workspaceId, family);
        if (locked == null) {
            throw new IllegalStateException("Relationship signal family state disappeared after creation");
        }
        return !familyStateCreated
            && locked.getLastAttemptAt() != null
            && !attemptedAt.isAfter(locked.getLastAttemptAt());
    }

    /** Loads the last-known active candidates once for cross-family admission after detector failures. */
    public List<RelationshipSignal> activeCandidates(int workspaceId) {
        return signalMapper.findActiveForActor(workspaceId, 0);
    }

    /** Applies the hard workspace cap over the canonical global deterministic order. */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED)
    public int enforceWorkspaceCap(
            int workspaceId,
            LocalDateTime resolvedAt) {
        List<RelationshipSignal> ranked = signalMapper.findActiveForActor(workspaceId, 0);
        if (ranked.size() <= WORKSPACE_CAP) {
            return 0;
        }
        List<Long> overflow = new ArrayList<>();
        for (int index = WORKSPACE_CAP; index < ranked.size(); index++) {
            overflow.add(ranked.get(index).getId());
        }
        if (!overflow.isEmpty()) {
            return signalMapper.resolveByIds(workspaceId, overflow, resolvedAt);
        }
        return 0;
    }
}
