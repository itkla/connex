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
 *
 * <p>The gate has two strengths, and which one a caller needs is decided by whether it invokes a
 * provider. {@link #isAiUsable(AiFeature)} is the full gate and includes provider readiness;
 * {@link #isFeatureGoverned(AiFeature)} stops at governance and is what the deterministic parts of an
 * AI surface use.
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

    /**
     * Evaluates everything that governs an AI feature except the provider itself: the instance
     * feature switch, the workspace's organization-level AI kill switch, active membership, and
     * {@link Permission#AI_USE}.
     *
     * <p>This is the correct gate for the deterministic parts of an AI surface — the ones that never
     * reach a provider. A typed watch is the standing example: whether it fired is decided entirely
     * by the warmth, task, and deal-risk models, and no completion is ever requested. Gating those on
     * provider readiness would mean a workspace that has not finished configuring BYOP, or whose
     * provider is briefly unusable, could neither create a watch nor have an existing one evaluated —
     * silently dropping conditions that a provider outage has nothing to do with.
     *
     * <p>What it deliberately keeps is every governance fact. A disabled kill switch, an organization
     * that has switched the assistant off, a revoked membership, or a lost {@code AI_USE} still stops
     * the surface here, so switching AI off never leaves a deterministic side channel running.
     *
     * <p>Anything that performs real provider egress — a brief, a chat turn, a generated narrative —
     * must keep using {@link #isAiUsable(AiFeature)} instead, because for those the absence of a
     * usable provider genuinely means the feature cannot run.
     *
     * @param feature feature to evaluate
     * @return true when this member may use the feature's provider-independent behaviour
     */
    public boolean isFeatureGoverned(AiFeature feature) {
        return hasFeaturePermission(feature);
    }

    /**
     * Requires the selected AI feature to pass the provider-independent governance gate.
     *
     * @param feature feature to require
     * @throws ForbiddenException when the instance, workspace, membership, or permission says no
     * @see #isFeatureGoverned(AiFeature)
     */
    public void requireFeatureGoverned(AiFeature feature) {
        if (!isFeatureGoverned(feature)) {
            throw new ForbiddenException("AI features are not available");
        }
    }

    /** Resolves the current fail-closed privacy mode after the complete feature gate passes. */
    public AiPrivacyMode privacyModeIfUsable(AiFeature feature) {
        if (!hasFeaturePermission(feature)) {
            return AiPrivacyMode.MASKED;
        }
        AiProviderReadiness readiness = providerReadiness.getIfAvailable();
        if (readiness == null) {
            return AiPrivacyMode.MASKED;
        }
        int orgId = workspaceService.getCurrentOrgId();
        return readiness.isReadyForOrg(orgId)
                ? readiness.privacyModeForOrg(orgId)
                : AiPrivacyMode.MASKED;
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
                && workspaceService.isMember(workspaceId, actorId)
                && workspaceService.permissionsFor(workspaceId, actorId).contains(Permission.AI_USE);
    }
}
