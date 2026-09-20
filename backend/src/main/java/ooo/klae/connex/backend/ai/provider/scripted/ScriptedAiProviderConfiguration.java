package ooo.klae.connex.backend.ai.provider.scripted;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import ooo.klae.connex.backend.ai.AiProperties;
import tools.jackson.databind.ObjectMapper;

/**
 * The one place scripted provider beans enter the application context.
 *
 * <p>Activation copies the seeder precedent in full: a dedicated Spring profile <em>and</em> an
 * explicit property. Both are necessary and neither is sufficient, and
 * {@code DeploymentProfileValidator} refuses the boot before this context exists when the profile
 * appears under a declared deployment profile, outside dev or test, without the flag, or when the
 * flag appears without the profile.
 *
 * <p>The fifth layer is the decisive one and lives in {@link ScriptedAiScriptLoader}: no script
 * ships in the artifact, and the loader fails the context unless
 * {@code connex.ai.scripted-provider.fixture-dir} names a readable directory holding at least one.
 * An instance that somehow defeated the first four layers would answer nothing.
 */
@Configuration(proxyBeanMethods = false)
@Profile(ScriptedAiProviderProfile.NAME)
@ConditionalOnProperty(prefix = "connex.ai.scripted-provider", name = "enabled", havingValue = "true")
public class ScriptedAiProviderConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(ScriptedAiProviderConfiguration.class);

    /**
     * Loads and validates the configured scripts, failing the context when any rule is violated.
     * @param aiProperties bound AI configuration
     * @param objectMapper shared JSON mapper
     * @return the loaded scripts
     */
    @Bean
    ScriptedAiScriptLoader scriptedAiScriptLoader(
            AiProperties aiProperties,
            ObjectMapper objectMapper) {
        ScriptedAiScriptLoader loader = new ScriptedAiScriptLoader(
                aiProperties.getScriptedProvider().getFixtureDir(), objectMapper);
        log.warn("Scripted AI provider active: the real openai_compatible adapter is replaced by "
                + "{} fixture scripts. This profile is refused under every deployment profile.",
                loader.scripts().size());
        return loader;
    }

    /**
     * Creates the bounded in-JVM journal of received provider requests.
     * @return the request journal
     */
    @Bean
    ScriptedAiRequestJournal scriptedAiRequestJournal() {
        return new ScriptedAiRequestJournal();
    }

    /**
     * Creates the scripted provider adapter.
     * @param scripts loaded scripts
     * @param journal bounded request journal
     * @param interceptors loop-thread hooks; empty in the running application
     * @return the scripted provider
     */
    @Bean
    ScriptedAiProvider scriptedAiProvider(
            ScriptedAiScriptLoader scripts,
            ScriptedAiRequestJournal journal,
            ObjectProvider<ScriptedAiStepInterceptor> interceptors) {
        return new ScriptedAiProvider(scripts, journal, interceptors.orderedStream().toList());
    }
}
