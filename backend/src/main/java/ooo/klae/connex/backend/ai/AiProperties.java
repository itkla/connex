package ooo.klae.connex.backend.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Instance-wide AI configuration, bound from {@code connex.ai.*} /
 * {@code CONNEX_AI_*}. The feature gate also requires a per-org configured and
 * enabled BYOP provider, arriving in a later PR, so AI features fail closed.
 */
@Data
@Component
@ConfigurationProperties(prefix = "connex.ai")
public class AiProperties {

    /**
     * Instance-level kill switch for all AI features. Defaults to false so a
     * deployment ships with AI dormant; the feature gate additionally requires a
     * per-org configured and enabled BYOP provider, arriving in a later PR, so AI
     * features fail closed.
     */
    private boolean enabled = false;
}
