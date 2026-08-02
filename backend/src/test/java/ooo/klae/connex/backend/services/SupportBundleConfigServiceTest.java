package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Guards the closed configuration allowlist that {@code config.json} is built from.
 */
class SupportBundleConfigServiceTest {

    @Test
    void noAllowlistedKeyLooksCredentialOrLocationBearing() {
        for (String key : SupportBundleConfigService.SAFE_CONFIG_KEYS) {
            for (String forbidden : SupportBundleConfigService.FORBIDDEN_KEY_SEGMENTS) {
                assertFalse(key.contains(forbidden),
                    "Allowlisted config key '" + key + "' contains the forbidden segment '"
                        + forbidden + "'. Secret- and location-bearing keys must be absent from a "
                        + "support bundle entirely, never masked.");
            }
        }
    }

    @Test
    void allowlistIsUniqueAndNonEmpty() {
        List<String> keys = SupportBundleConfigService.SAFE_CONFIG_KEYS;
        assertFalse(keys.isEmpty());
        assertEquals(keys.size(), keys.stream().distinct().count());
    }

    @Test
    void emitsOnlyAllowlistedKeysThatAreConfigured() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("connex.deployment.profile", "on-prem");
        environment.setProperty("connex.ai.enabled", "false");
        environment.setProperty("connex.mail.password", "sentinel-secret-value");
        environment.setProperty("spring.datasource.url", "jdbc:mysql://db.internal:3306/connexdb");

        Map<String, String> configuration = new SupportBundleConfigService(environment)
            .safeConfiguration().values();

        assertEquals("on-prem", configuration.get("connex.deployment.profile"));
        assertEquals("false", configuration.get("connex.ai.enabled"));
        assertFalse(configuration.containsKey("connex.mail.password"));
        assertFalse(configuration.containsKey("spring.datasource.url"));
        assertFalse(configuration.toString().contains("sentinel-secret-value"));
        assertFalse(configuration.toString().contains("db.internal"));
    }

    @Test
    void dropsAnAllowlistedKeyWhoseValueLooksLikeAConnectionStringOrToken() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("connex.deployment.profile", "jdbc:mysql://db.internal:3306/x");
        environment.setProperty("connex.signup.mode", "https://tenant.example.com/callback");
        environment.setProperty("connex.mail.port", "587");

        SupportBundleConfigService.SafeConfiguration configuration =
            new SupportBundleConfigService(environment).safeConfiguration();

        assertFalse(configuration.values().containsKey("connex.deployment.profile"));
        assertFalse(configuration.values().containsKey("connex.signup.mode"));
        assertEquals("587", configuration.values().get("connex.mail.port"));
        assertEquals("unsafe_value_shape",
            configuration.omissions().get("config:connex.deployment.profile"));
        assertFalse(configuration.toString().contains("db.internal"));
    }

    @Test
    void declaresEveryUnresolvableKeyRatherThanSilentlyDroppingIt() {
        SupportBundleConfigService.SafeConfiguration configuration =
            new SupportBundleConfigService(new MockEnvironment()).safeConfiguration();

        assertTrue(configuration.values().isEmpty());
        for (String key : SupportBundleConfigService.SAFE_CONFIG_KEYS) {
            assertEquals("not_resolvable", configuration.omissions().get("config:" + key),
                "Key " + key + " was dropped without declaring why; a defaulted-off flag would "
                    + "otherwise be indistinguishable from an uncollected one");
        }
    }
}
