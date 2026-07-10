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
 * organization has an enabled, fully-configured BYOP provider. No
 * {@link AiProviderReadiness} bean exists in this PR, so all AI use is denied
 * until the BYOP settings PR lands. This gate runs on the request thread only
 * and uses the current tenant context through {@link WorkspaceService}.
 */
@Service
@RequiredArgsConstructor
public class AiFeatureGate {
    private final AiProperties aiProperties;
    private final WorkspaceService workspaceService;
    private final ObjectProvider<AiProviderReadiness> providerReadiness;

    public boolean isAiUsable() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int orgId = workspaceService.getCurrentOrgId();
        int actorId = workspaceService.getCurrentUserId();
        return aiProperties.isEnabled()
                && workspaceService.permissionsFor(workspaceId, actorId).contains(Permission.AI_USE)
                && readiness(orgId);
    }

    public void requireAiUsable() {
        if (!isAiUsable()) {
            throw new ForbiddenException("AI features are not available");
        }
    }

    private boolean readiness(int orgId) {
        AiProviderReadiness readiness = providerReadiness.getIfAvailable();
        return readiness != null && readiness.isReadyForOrg(orgId);
    }
}
