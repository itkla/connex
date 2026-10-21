package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void trajectoryProfileDeclaresEveryScriptedMissionModel() throws Exception {
        var environment = new org.springframework.core.env.StandardEnvironment();
        var configuration = AiAgentScriptedTrajectoryTest.class.getAnnotation(
                org.springframework.test.context.TestPropertySource.class);
        org.springframework.test.context.support.TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                environment, configuration.properties());
        var bound = org.springframework.boot.context.properties.bind.Binder.get(environment).bind(
                "connex.ai", org.springframework.boot.context.properties.bind.Bindable.of(AiProperties.class)).get();
        var trajectoryGate = new AiAgentCapabilityGate(bound, initializer, featureGate, invocation,
                providers, governance, workspaces);
        var directory = java.nio.file.Path.of(java.util.Objects.requireNonNull(
                getClass().getResource("/ai/scripted")).toURI()).resolve(environment.getRequiredProperty(
                        "connex.test.scripted-fixture-subdirectory"));
        var loader = new ooo.klae.connex.backend.ai.provider.scripted.ScriptedAiScriptLoader(
                directory.toString(), tools.jackson.databind.json.JsonMapper.builder().build());
        assertEquals(2, bound.getMissionCapabilities().size());
        for (var script : loader.scripts()) {
            assertDoesNotThrow(() -> trajectoryGate.requireTarget(target(
                    "openai_compatible", "https://scripted.invalid/v1", script.capabilityClass().modelId())));
        }
    }

    @Test
    void browserStackBindsItsExactScriptedMissionCapability() throws Exception {
        Object document = new org.yaml.snakeyaml.Yaml().load(java.nio.file.Files.readString(
                java.nio.file.Path.of("../.github/workflows/ci.yml")));
        java.util.Map<?, ?> root = org.junit.jupiter.api.Assertions.assertInstanceOf(java.util.Map.class, document);
        java.util.Map<?, ?> jobs = org.junit.jupiter.api.Assertions.assertInstanceOf(java.util.Map.class, root.get("jobs"));
        java.util.Map<?, ?> frontend = org.junit.jupiter.api.Assertions.assertInstanceOf(java.util.Map.class, jobs.get("frontend-tests"));
        java.util.List<?> steps = org.junit.jupiter.api.Assertions.assertInstanceOf(java.util.List.class, frontend.get("steps"));
        String configuration = steps.stream().filter(java.util.Map.class::isInstance).map(step -> (java.util.Map<?, ?>) step)
                .filter(step -> "Boot backend (dev profile, fresh schema)".equals(step.get("name")))
                .map(step -> org.junit.jupiter.api.Assertions.assertInstanceOf(java.util.Map.class, step.get("env")))
                .map(env -> org.junit.jupiter.api.Assertions.assertInstanceOf(String.class, env.get("SPRING_APPLICATION_JSON")))
                .findFirst().orElseThrow();
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource(
                "mission-browser-test", java.util.Map.of("SPRING_APPLICATION_JSON", configuration)));
        new org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new org.springframework.boot.SpringApplication());
        var bound = org.springframework.boot.context.properties.bind.Binder.get(environment).bind(
                "connex.ai", org.springframework.boot.context.properties.bind.Bindable.of(AiProperties.class)).get();
        assertTrue(bound.isMissionsEnabled());
        assertTrue(bound.isMissionsSingleJvmConfirmed());
        assertEquals(1, bound.getMissionCapabilities().size());
        var declaration = bound.getMissionCapabilities().getFirst();
        assertEquals("openai_compatible", declaration.getProvider());
        assertEquals("http://127.0.0.1:8080/v1", declaration.getEndpoint());
        assertEquals("scripted-json", declaration.getModel());
        assertEquals("missions_v1", declaration.getCapability());

        String browserSpec = java.nio.file.Files.readString(java.nio.file.Path.of(
                "../frontend/test/e2e/ask-connex-mission-proposals.spec.ts"));
        var model = java.util.regex.Pattern.compile(
                "configureScriptedAiProvider\\(api, workspaceId, csrf, '([^']+)'\\)").matcher(browserSpec);
        assertTrue(model.find(), "Mission browser spec must explicitly select its scripted model");
        String providerSetup = java.nio.file.Files.readString(java.nio.file.Path.of(
                "../frontend/test/e2e/support/api.ts"));
        var provider = java.util.regex.Pattern.compile("provider: \"([^\"]+)\"").matcher(providerSetup);
        var endpoint = java.util.regex.Pattern.compile("endpoint: \"([^\"]+)\"").matcher(providerSetup);
        assertTrue(provider.find());
        assertTrue(endpoint.find());
        var fixture = tools.jackson.databind.json.JsonMapper.builder().build().readTree(
                java.nio.file.Files.readString(java.nio.file.Path.of(
                        "../frontend/test/e2e/fixtures/ai-scripted/e2e_mission_proposals.json")));
        assertEquals(fixture.path("capabilityClass").asString(), model.group(1));
        var browserGate = new AiAgentCapabilityGate(bound, initializer, featureGate, invocation,
                providers, governance, workspaces);
        assertDoesNotThrow(() -> browserGate.requireTarget(target(provider.group(1), endpoint.group(1), model.group(1))));
    }
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
