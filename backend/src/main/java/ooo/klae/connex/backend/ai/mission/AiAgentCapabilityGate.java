package ooo.klae.connex.backend.ai.mission;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPromptBudget;
import ooo.klae.connex.backend.ai.provider.AiProviderTarget;
import ooo.klae.connex.backend.exceptions.AiAgentUnavailableException;
import ooo.klae.connex.backend.services.AiProviderConfigService;
import ooo.klae.connex.backend.services.AiWorkspaceGovernanceService;
import ooo.klae.connex.backend.services.WorkspaceService;

/** Exact endpoint/model operator declaration, independent of normal AI readiness and governance. */
@Service
@RequiredArgsConstructor
public class AiAgentCapabilityGate {
    private final AiProperties properties;
    private final AiAgentPrivacyInitializer initializer;
    private final AiFeatureGate featureGate;
    private final AiInvocationService invocationService;
    private final AiProviderConfigService providerConfig;
    private final AiWorkspaceGovernanceService governance;
    private final WorkspaceService workspaces;

    /** Refuses before claim, without spending an attempt or mission allowance. */
    public byte[] requireReady() {
        if (!initializer.isReady() || !properties.isMissionsEnabled() || !properties.isMissionsSingleJvmConfirmed()
                || !governance.isEnabled(workspaces.getCurrentWorkspaceId())) {
            throw new AiAgentUnavailableException();
        }
        featureGate.requireAiUsable(AiFeature.ASSISTANT_CHAT);
        AiAssistantPromptBudget.requireAssistantContextFloor(
                invocationService.currentProviderCapabilities(AiFeature.ASSISTANT_CHAT).contextWindowTokens());
        return requireTarget(providerConfig.resolveForOrg(
                workspaces.getCurrentOrgId(), workspaces.getCurrentUserId()).target());
    }

    /** Revalidates the exact target at dispatch, never a provider-family wildcard. */
    public byte[] requireTarget(AiProviderTarget target) {
        if (!properties.isMissionsEnabled() || !properties.isMissionsSingleJvmConfirmed()) {
            throw new AiAgentUnavailableException();
        }
        boolean declared = properties.getMissionCapabilities().stream().anyMatch(declaration ->
                "missions_v1".equals(declaration.getCapability())
                && Objects.equals(declaration.getProvider(), target.provider())
                && Objects.equals(declaration.getEndpoint(), target.endpoint())
                && declaration.getEndpoint() != null && !declaration.getEndpoint().isBlank()
                && Objects.equals(declaration.getModel(), target.modelId()));
        if (!declared) {
            throw new AiAgentUnavailableException();
        }
        return hash("missions_v1\n" + target.provider() + "\n" + target.endpoint() + "\n" + target.modelId());
    }

    /** Stable binary digest for canonical metadata, never logged. */
    public static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
