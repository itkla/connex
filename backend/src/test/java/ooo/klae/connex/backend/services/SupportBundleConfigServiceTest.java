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
            .safeConfiguration();

        assertEquals("on-prem", configuration.get("connex.deployment.profile"));
        assertEquals("false", configuration.get("connex.ai.enabled"));
        assertFalse(configuration.containsKey("connex.mail.password"));
        assertFalse(configuration.containsKey("spring.datasource.url"));
        assertFalse(configuration.toString().contains("sentinel-secret-value"));
        assertFalse(configuration.toString().contains("db.internal"));
    }

    @Test
    void omitsUnsetKeysRatherThanEmittingNull() {
        Map<String, String> configuration = new SupportBundleConfigService(new MockEnvironment())
            .safeConfiguration();

        assertTrue(configuration.isEmpty());
    }
}
