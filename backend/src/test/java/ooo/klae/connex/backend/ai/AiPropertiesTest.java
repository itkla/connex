package ooo.klae.connex.backend.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class AiPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class))
            .withUserConfiguration(AiProperties.class);

    @Test
    void instanceAiRequiresExplicitOperatorOptIn() throws IOException {
        ClassPathResource applicationConfig = new ClassPathResource("application.yml");
        String yaml = new String(applicationConfig.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertFalse(new AiProperties().isEnabled());
        assertTrue(new AiProperties().getNat64Prefixes().isEmpty());
        assertEquals(2, new AiProperties().getMaxConcurrentMediaRequests());
        assertEquals(1, new AiProperties().getMaxConcurrentMediaRequestsPerOrg());
        assertEquals(67108864, new AiProperties().getMaxMediaWorkingBytes());
        assertEquals(300, new AiProperties().getInvocationQuotaAttemptsPerOrg());
        assertEquals(16384, new AiProperties().getAssistantMaxOutputTokens());
        assertTrue(new AiProperties().isAssistantThinkingEnabled());
        assertEquals(32, new AiProperties().getGenerationMaxHandlesPerUser());
        assertEquals(67108864, new AiProperties().getGenerationMaxRetainedResultBytes());
        assertEquals(33554432, new AiProperties().getGenerationMaxRetainedResultBytesPerWorkspace());
        assertEquals(16777216, new AiProperties().getGenerationMaxRetainedResultBytesPerUser());
        assertTrue(yaml.contains("enabled: ${CONNEX_AI_ENABLED:false}"));
        assertTrue(yaml.contains("deal-brief: ${CONNEX_AI_FEATURES_DEAL_BRIEF:true}"));
        assertTrue(yaml.contains("assistant-chat: ${CONNEX_AI_FEATURES_ASSISTANT_CHAT:true}"));
        assertTrue(yaml.contains("nat64-prefixes: ${CONNEX_AI_NAT64_PREFIXES:}"));
        assertTrue(yaml.contains("max-concurrent-media-requests: ${CONNEX_AI_MAX_CONCURRENT_MEDIA_REQUESTS:2}"));
        assertTrue(yaml.contains(
                "invocation-quota-attempts-per-org: ${CONNEX_AI_INVOCATION_QUOTA_ATTEMPTS_PER_ORG:300}"));
        assertTrue(yaml.contains(
                "assistant-max-output-tokens: ${CONNEX_AI_ASSISTANT_MAX_OUTPUT_TOKENS:16384}"));
        assertTrue(yaml.contains(
                "assistant-thinking-enabled: ${CONNEX_AI_ASSISTANT_THINKING_ENABLED:true}"));
        assertTrue(yaml.contains(
                "generation-max-retained-result-bytes: ${CONNEX_AI_GENERATION_MAX_RETAINED_RESULT_BYTES:67108864}"));
        assertTrue(yaml.contains(
                "generation-max-retained-result-bytes-per-workspace: ${CONNEX_AI_GENERATION_MAX_RETAINED_RESULT_BYTES_PER_WORKSPACE:33554432}"));
        assertTrue(yaml.contains(
                "generation-max-retained-result-bytes-per-user: ${CONNEX_AI_GENERATION_MAX_RETAINED_RESULT_BYTES_PER_USER:16777216}"));
    }

    @Test
    void featuresDefaultOnOnlyUnderEnabledMasterSwitch() {
        AiProperties properties = new AiProperties();

        for (AiFeature feature : AiFeature.values()) {
            assertFalse(properties.isFeatureEnabled(feature));
        }

        properties.setEnabled(true);

        for (AiFeature feature : AiFeature.values()) {
            assertTrue(properties.isFeatureEnabled(feature));
        }
    }

    @Test
    void explicitFeatureFalseDisablesOnlyThatFeature() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        Map<AiFeature, Boolean> features = new EnumMap<>(AiFeature.class);
        features.put(AiFeature.DEAL_BRIEF, false);
        features.put(AiFeature.ASSISTANT_CHAT, false);
        properties.setFeatures(features);

        assertFalse(properties.isFeatureEnabled(AiFeature.DEAL_BRIEF));
        assertFalse(properties.isFeatureEnabled(AiFeature.ASSISTANT_CHAT));
        assertTrue(properties.isFeatureEnabled(AiFeature.REPORT_NARRATIVE));
    }

    @Test
    void relaxedBindingMapsFeatureAndDurationKnobs() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("connex.ai.enabled", "true")
                .withProperty("connex.ai.features.deal-brief", "false")
                .withProperty("connex.ai.features.assistant-chat", "false")
                .withProperty("connex.ai.assistant-max-output-tokens", "7777")
                .withProperty("connex.ai.assistant-thinking-enabled", "false")
                .withProperty("connex.ai.invocation-quota-window", "15m");

        AiProperties properties = Binder.get(environment)
                .bind("connex.ai", Bindable.of(AiProperties.class))
                .orElseThrow(() -> new IllegalStateException("AI properties did not bind"));

        assertFalse(properties.isFeatureEnabled(AiFeature.DEAL_BRIEF));
        assertFalse(properties.isFeatureEnabled(AiFeature.ASSISTANT_CHAT));
        assertTrue(properties.isFeatureEnabled(AiFeature.REPORT_NARRATIVE));
        assertEquals(7777, properties.getAssistantMaxOutputTokens());
        assertFalse(properties.isAssistantThinkingEnabled());
        assertEquals(Duration.ofMinutes(15), properties.getInvocationQuotaWindow());
    }

    @Test
    void nonPositiveAssistantOutputTokenLimitsFailAtStartup() {
        contextRunner.run(context -> assertNull(context.getStartupFailure()));
        contextRunner
                .withPropertyValues("connex.ai.assistant-max-output-tokens=1")
                .run(context -> assertNull(context.getStartupFailure()));
        for (String invalid : List.of("0", "-1")) {
            contextRunner
                    .withPropertyValues(
                            "connex.ai.assistant-max-output-tokens=" + invalid)
                    .run(context -> assertNotNull(
                            context.getStartupFailure(),
                            "Expected startup failure for output-token limit " + invalid));
        }
    }
}
