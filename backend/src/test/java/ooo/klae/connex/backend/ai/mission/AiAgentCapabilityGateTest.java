package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.provider.AiProviderTarget;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.services.AiProviderConfigService;
import ooo.klae.connex.backend.services.AiWorkspaceGovernanceService;
import ooo.klae.connex.backend.services.WorkspaceService;

class AiAgentCapabilityGateTest {
    private final AiProperties properties = new AiProperties();
    private final AiAgentPrivacyInitializer initializer = mock(AiAgentPrivacyInitializer.class);
    private final AiFeatureGate featureGate = mock(AiFeatureGate.class);
    private final AiInvocationService invocation = mock(AiInvocationService.class);
    private final AiAgentCapabilityGate gate = new AiAgentCapabilityGate(properties, initializer,
            featureGate, invocation, mock(AiProviderConfigService.class),
            mock(AiWorkspaceGovernanceService.class), mock(WorkspaceService.class));

    @Test
    void unavailableInitializerRefusesBeforeProviderOrFeatureWork() {
        properties.setMissionsEnabled(true);
        properties.setMissionsSingleJvmConfirmed(true);

        assertThrows(ServiceUnavailableException.class, gate::requireReady);
        verifyNoInteractions(featureGate, invocation);
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
