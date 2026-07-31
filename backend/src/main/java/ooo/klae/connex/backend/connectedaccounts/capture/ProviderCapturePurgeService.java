package ooo.klae.connex.backend.connectedaccounts.capture;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.mappers.ProviderCaptureMapper;

/**
 * Idempotent tenant-local purge boundary for one user and provider.
 */
@Service
@RequiredArgsConstructor
public class ProviderCapturePurgeService {
    private final ProviderCaptureMapper captureMapper;

    /** Removes all admitted and source-owned data, policy, decisions, and cursors. */
    @Transactional
    public void purge(int workspaceId, int userId, String provider) {
        captureMapper.deleteProviderActivities(workspaceId, userId, provider);
        captureMapper.deleteInteractions(workspaceId, userId, provider);
        captureMapper.deleteSyncStates(workspaceId, userId, provider);
        captureMapper.deleteUserPolicy(workspaceId, userId, provider);
        captureMapper.deleteDecisions(workspaceId, userId, provider);
        long residuals =
            captureMapper.countUserProviderResiduals(workspaceId, userId, provider);
        if (residuals != 0) {
            throw new IllegalStateException("Provider purge left tenant residuals");
        }
    }

    /** Clears nullable workspace-policy authorship retained after account erasure. */
    @Transactional
    public void clearAccountReferences(int workspaceId, int userId) {
        captureMapper.clearWorkspacePolicyUpdater(workspaceId, userId);
    }

    /** Removes one user's provider data throughout the currently routed tenant catalog. */
    @Transactional
    public void purgeAccountCatalog(int userId, String provider) {
        captureMapper.deleteProviderActivitiesAnywhere(userId, provider);
        captureMapper.deleteInteractionsAnywhere(userId, provider);
        captureMapper.deleteSyncStatesAnywhere(userId, provider);
        captureMapper.deleteUserPolicyAnywhere(userId, provider);
        captureMapper.deleteDecisionsAnywhere(userId, provider);
    }

    /** Clears workspace-policy authorship throughout the currently routed tenant catalog. */
    @Transactional
    public void clearAccountReferencesInCatalog(int userId) {
        captureMapper.clearWorkspacePolicyUpdaterAnywhere(userId);
    }
}
