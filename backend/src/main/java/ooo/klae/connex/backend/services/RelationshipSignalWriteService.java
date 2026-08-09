package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.RelationshipSignal;
import ooo.klae.connex.backend.mappers.RelationshipSignalMapper;

/** Isolated tenant writes for detector reconciliation and bounded selection. */
@Service
@RequiredArgsConstructor
public class RelationshipSignalWriteService {
    static final int WORKSPACE_CAP = 50;

    private final RelationshipSignalMapper signalMapper;

    /** Replaces one successfully computed family without disturbing failed families. */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED)
    public void replaceFamily(
            int workspaceId,
            String family,
            String generationToken,
            List<RelationshipSignal> candidates,
            LocalDateTime attemptedAt,
            LocalDateTime evidenceAsOf) {
        signalMapper.ensureFamilyState(workspaceId, family, attemptedAt);
        signalMapper.lockFamilyState(workspaceId, family);
        for (RelationshipSignal candidate : candidates) {
            signalMapper.upsertSignal(candidate);
        }
        signalMapper.resolveMissing(workspaceId, family, generationToken, attemptedAt);
        signalMapper.upsertFamilyAvailable(
            workspaceId, family, attemptedAt, evidenceAsOf);
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
        signalMapper.ensureFamilyState(workspaceId, family, attemptedAt);
        signalMapper.lockFamilyState(workspaceId, family);
        signalMapper.upsertFamilyUnavailable(
            workspaceId, family, attemptedAt, errorCode);
    }

    /** Applies the hard workspace cap over the canonical global deterministic order. */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED)
    public void enforceWorkspaceCap(
            int workspaceId,
            LocalDateTime resolvedAt) {
        List<RelationshipSignal> ranked = signalMapper.findActiveForActor(workspaceId, 0);
        if (ranked.size() <= WORKSPACE_CAP) {
            return;
        }
        List<Long> overflow = new ArrayList<>();
        for (int index = WORKSPACE_CAP; index < ranked.size(); index++) {
            overflow.add(ranked.get(index).getId());
        }
        if (!overflow.isEmpty()) {
            signalMapper.resolveByIds(workspaceId, overflow, resolvedAt);
        }
    }
}
