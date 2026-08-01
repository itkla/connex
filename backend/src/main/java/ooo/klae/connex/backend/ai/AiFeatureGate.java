package ooo.klae.connex.backend.ai;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;

/**
 * Fails closed unless the instance AI kill switch is enabled, the current actor
 * has {@link Permission#AI_USE} in the active workspace, and the active
 * organization has an enabled, fully-configured BYOP provider. This gate runs on the request
 * thread only and uses the current tenant context through {@link WorkspaceService}.
 */
@Service
@RequiredArgsConstructor
public class AiFeatureGate {
    private final AiProperties aiProperties;
    private final WorkspaceService workspaceService;
    private final ObjectProvider<AiProviderReadiness> providerReadiness;

    /**
     * Evaluates instance, feature, permission, and provider readiness in fail-closed order.
     * @param feature feature to evaluate
     * @return true when the feature may invoke the configured provider
     */
    public boolean isAiUsable(AiFeature feature) {
        if (!aiProperties.isFeatureEnabled(feature)) {
            return false;
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        if (!workspaceService.permissionsFor(workspaceId, actorId).contains(Permission.AI_USE)) {
            return false;
        }
        return readiness(workspaceService.getCurrentOrgId(), feature.requiresImageInput());
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
}
