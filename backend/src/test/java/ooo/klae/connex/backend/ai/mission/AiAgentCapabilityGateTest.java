package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.provider.AiProviderTarget;
import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;
import ooo.klae.connex.backend.ai.provider.ResolvedAiProvider;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.services.AiProviderConfigService;
import ooo.klae.connex.backend.services.AiWorkspaceGovernanceService;
import ooo.klae.connex.backend.services.WorkspaceService;

class AiAgentCapabilityGateTest {
    private final AiProperties properties = new AiProperties();
    private final AiAgentPrivacyInitializer initializer = mock(AiAgentPrivacyInitializer.class);
    private final AiFeatureGate featureGate = mock(AiFeatureGate.class);
    private final AiInvocationService invocation = mock(AiInvocationService.class);
    private final AiWorkspaceGovernanceService governance = mock(AiWorkspaceGovernanceService.class);
    private final AiProviderConfigService providers = mock(AiProviderConfigService.class);
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final AiAgentCapabilityGate gate = new AiAgentCapabilityGate(properties, initializer,
            featureGate, invocation, providers, governance, workspaces);

    @Test
    void unavailableInitializerRefusesBeforeProviderOrFeatureWork() {
        properties.setMissionsEnabled(true);
        properties.setMissionsSingleJvmConfirmed(true);

        declare("provider", "https://provider.test/v1", "model");
        when(workspaces.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaces.getCurrentOrgId()).thenReturn(11);
        when(workspaces.getCurrentUserId()).thenReturn(13);
        when(governance.isEnabled(7)).thenReturn(true);
        AiProviderCapabilities capabilities = mock(AiProviderCapabilities.class);
        when(capabilities.contextWindowTokens()).thenReturn(65536);
        when(invocation.currentProviderCapabilities(AiFeature.ASSISTANT_CHAT)).thenReturn(capabilities);
        ResolvedAiProvider resolved = mock(ResolvedAiProvider.class);
        when(resolved.target()).thenReturn(target("provider", "https://provider.test/v1", "model"));
        when(providers.resolveForOrg(11, 13)).thenReturn(resolved);
        when(initializer.isReady()).thenReturn(false);

        assertThrows(ServiceUnavailableException.class, gate::requireReady);
        verifyNoInteractions(featureGate, invocation);
        when(initializer.isReady()).thenReturn(true);
        assertDoesNotThrow(gate::requireReady);
        verify(featureGate).requireAiUsable(AiFeature.ASSISTANT_CHAT);
        verify(providers).resolveForOrg(11, 13);
    }

    @Test
    void declarationRequiresExactProviderEndpointAndModel() {
        declare("provider", "https://provider.test/v1", "model");
        byte[] expected = AiAgentCapabilityGate.hash("missions_v1\nprovider\nhttps://provider.test/v1\nmodel");

        assertArrayEquals(expected, gate.requireTarget(target("provider", "https://provider.test/v1", "model")));
        assertThrows(ServiceUnavailableException.class, () -> gate.requireTarget(target("other", "https://provider.test/v1", "model")));
        assertThrows(ServiceUnavailableException.class, () -> gate.requireTarget(target("provider", "https://other.test/v1", "model")));
        assertThrows(ServiceUnavailableException.class, () -> gate.requireTarget(target("provider", "https://provider.test/v1", "other")));
    }

    @Test
    void noEndpointWildcardAndNoCapabilityDowngradeCanAuthorizeDispatch() {
        declare("provider", null, "model");
        assertThrows(ServiceUnavailableException.class, () -> gate.requireTarget(target("provider", null, "model")));
        declare("provider", "https://provider.test/v1", "model");
        properties.getMissionCapabilities().getFirst().setCapability("none");
        assertThrows(ServiceUnavailableException.class, () -> gate.requireTarget(target("provider", "https://provider.test/v1", "model")));
    }

    @Test
    void disablingMissionOrSingleJvmDeclarationRefusesAnAlreadySelectedTarget() {
        declare("provider", "https://provider.test/v1", "model");
        properties.setMissionsEnabled(false);
        assertThrows(ServiceUnavailableException.class, () -> gate.requireTarget(target("provider", "https://provider.test/v1", "model")));
        properties.setMissionsEnabled(true);
        properties.setMissionsSingleJvmConfirmed(false);
        assertThrows(ServiceUnavailableException.class, () -> gate.requireTarget(target("provider", "https://provider.test/v1", "model")));
    }

    private void declare(String provider, String endpoint, String model) {
        properties.setMissionsEnabled(true);
        properties.setMissionsSingleJvmConfirmed(true);
        AiProperties.MissionCapability declaration = new AiProperties.MissionCapability();
        declaration.setProvider(provider);
        declaration.setEndpoint(endpoint);
        declaration.setModel(model);
        declaration.setCapability("missions_v1");
        properties.setMissionCapabilities(List.of(declaration));
    }

    private static AiProviderTarget target(String provider, String endpoint, String model) {
        return new AiProviderTarget(provider, null, model, endpoint, null, null, null, false);
    }
}
