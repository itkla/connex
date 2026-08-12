package ooo.klae.connex.backend.ai;

import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.AiWorkspaceGovernanceService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;

/**
 * Fails closed unless the instance AI kill switch is enabled, the current actor
 * has {@link Permission#AI_USE} in the active workspace, and the active
 * organization has an enabled, fully-configured BYOP provider. This gate uses the current tenant
 * and security contexts from either the request thread or a propagated automation thread.
 */
@Service
@RequiredArgsConstructor
public class AiFeatureGate {
    private final AiProperties aiProperties;
    private final WorkspaceService workspaceService;
    private final ObjectProvider<AiProviderReadiness> providerReadiness;
    private final AiWorkspaceGovernanceService governanceService;

    /**
     * Evaluates instance, feature, permission, and provider readiness in fail-closed order.
     * @param feature feature to evaluate
     * @return true when the feature may invoke the configured provider
     */
    public boolean isAiUsable(AiFeature feature) {
        if (!hasFeaturePermission(feature)) {
            return false;
        }
        return readiness(workspaceService.getCurrentOrgId(), feature.requiresImageInput());
    }

    /**
     * Evaluates a text feature's instance switch, actor permission, and provider readiness while
     * returning the exact provider profile from the single readiness read.
     * @param feature text feature to evaluate
     * @param maxTokens feature output token cap
     * @param temperature feature sampling temperature
     * @return ready generation profile, or empty when the feature is unavailable
     */
    public Optional<AiGenerationProfile> generationProfileIfUsable(
            AiFeature feature, int maxTokens, double temperature) {
        if (!hasFeaturePermission(feature) || feature.requiresImageInput()) {
            return Optional.empty();
        }
        AiProviderReadiness readiness = providerReadiness.getIfAvailable();
        return readiness == null
                ? Optional.empty()
                : readiness.generationProfileForOrg(
                        workspaceService.getCurrentOrgId(), maxTokens, temperature);
    }

    /**
     * Requires the selected AI feature to pass the fail-closed gate.
     * @param feature feature to require
     */
    public void requireAiUsable(AiFeature feature) {
        if (!isAiUsable(feature)) {
            throw new ForbiddenException("AI features are not available");
        }
    }

    private boolean readiness(int orgId, boolean imageInput) {
        AiProviderReadiness readiness = providerReadiness.getIfAvailable();
        return readiness != null && (imageInput
                ? readiness.isImageInputReadyForOrg(orgId)
                : readiness.isReadyForOrg(orgId));
    }

    private boolean hasFeaturePermission(AiFeature feature) {
        if (!aiProperties.isFeatureEnabled(feature)) {
            return false;
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        return governanceService.isEnabled(workspaceId)
                && workspaceService.permissionsFor(workspaceId, actorId).contains(Permission.AI_USE);
    }
}
