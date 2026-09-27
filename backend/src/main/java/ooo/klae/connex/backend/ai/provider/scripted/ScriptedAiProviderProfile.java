package ooo.klae.connex.backend.ai.provider.scripted;

/**
 * Single declaration of the scripted-provider activation vocabulary.
 *
 * <p>The profile literal is referenced from four places that must never drift apart: the
 * {@code @Profile} on {@link ScriptedAiProviderConfiguration}, its negation on the real
 * OpenAI-compatible adapter, the {@code @ConditionalOnProperty} flag, and the pre-context startup
 * refusals in {@code DeploymentProfileValidator}. A literal repeated by hand in those four places
 * can be edited in three, which is why they all read these constants instead.
 */
public final class ScriptedAiProviderProfile {

    /** Spring profile that replaces the real OpenAI-compatible adapter with the scripted one. */
    public static final String NAME = "ai-scripted-provider";

    /** Second activation gate, forbidden under every deployment profile. */
    public static final String ENABLED_PROPERTY = "connex.ai.scripted-provider.enabled";

    /** Directory of scripts; nothing is read from the classpath and no script ships. */
    public static final String FIXTURE_DIR_PROPERTY = "connex.ai.scripted-provider.fixture-dir";

    private ScriptedAiProviderProfile() {
    }
}
