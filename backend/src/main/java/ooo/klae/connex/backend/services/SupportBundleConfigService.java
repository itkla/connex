package ooo.klae.connex.backend.services;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Produces the allowlisted configuration projection carried by a support bundle.
 *
 * <p>The allowlist is closed and constructive: a key is included because it was reviewed and
 * named here, never because it failed to match a deny pattern. Keys that describe credentials or
 * locations are absent entirely rather than masked — a masked key still discloses that the
 * setting exists and is populated, and a placeholder is indistinguishable from a real value once
 * the bundle has left the tenant.
 *
 * <p>{@link #FORBIDDEN_KEY_SEGMENTS} is a defensive backstop asserted by
 * {@code SupportBundleConfigServiceTest}, so a future key whose name looks credential- or
 * location-bearing cannot be added to the allowlist without the test failing. The match is a
 * plain substring, which occasionally rejects an innocent key whose name merely contains a
 * forbidden fragment. That is the intended direction of failure: a key excluded in error costs a
 * support engineer one datum, while a key admitted in error leaves the deployment.
 */
@Service
@RequiredArgsConstructor
public class SupportBundleConfigService {

    /**
     * The complete set of configuration keys a support bundle may disclose. Every entry describes
     * deployment posture — which profile is active and which features are enabled — and none
     * carries a credential, hostname, address, or identifier of a tenant or third party.
     */
    public static final List<String> SAFE_CONFIG_KEYS = List.of(
        "spring.flyway.enabled",
        "spring.flyway.baseline-on-migrate",
        "spring.flyway.baseline-version",
        "spring.sql.init.mode",
        "connex.deployment.profile",
        "connex.tenancy.enforce-scope",
        "connex.tenancy.routing.mode",
        "connex.maintenance.mode",
        "connex.workspaces.allow-self-service-creation",
        "connex.signup.mode",
        "connex.sso.enabled",
        "connex.social-login.google.enabled",
        "connex.social-login.microsoft.enabled",
        "connex.connected-accounts.google.enabled",
        "connex.connected-accounts.microsoft.enabled",
        "connex.connected-capture.scheduling-enabled",
        "connex.connected-capture.google.enabled",
        "connex.connected-capture.microsoft.enabled",
        "connex.ai.enabled",
        "connex.business-cards.enabled",
        "connex.sharing.enabled",
        "connex.notifications.scheduling-enabled",
        "connex.rules.scheduling-enabled",
        "connex.reports.scheduling-enabled",
        "connex.delivery.enabled",
        "connex.mail.enabled",
        "connex.mail.managed",
        "connex.mail.port",
        "connex.mail.starttls",
        "connex.mail.ssl");

    /**
     * Name fragments that disqualify a key from the allowlist. A key containing any of these is
     * either credential-bearing or identifies a host, tenant, or third party, and neither belongs
     * in an artefact that leaves the deployment.
     */
    public static final List<String> FORBIDDEN_KEY_SEGMENTS = List.of(
        "password",
        "passwd",
        "secret",
        "token",
        "credential",
        "authorization",
        "cookie",
        "session",
        "key",
        "client-id",
        "username",
        "email",
        "host",
        "url",
        "uri",
        "origin",
        "endpoint",
        "domain",
        "bucket",
        "catalog",
        "database",
        "datasource");

    private static final Pattern UNSAFE_VALUE = Pattern.compile(
        "(?i)(jdbc:|://|-----BEGIN |[A-Za-z0-9+/_-]{40,}={0,2}$)");

    private final Environment environment;

    /**
     * Returns the effective value of every allowlisted key that this instance has configured.
     *
     * <p>An unset key is omitted rather than emitted as null, so a reader can tell "not
     * configured" from "configured empty".
     *
     * <p>Both guards are enforced here at runtime, not merely asserted by a test. A key whose name
     * looks credential- or location-bearing is dropped even though it was allowlisted, and so is a
     * value that looks like a connection string, URL, PEM block, or long opaque token — an
     * allowlisted key can still be given a dangerous value by an operator. Every drop is reported
     * so the omission is visible in the manifest rather than silent.
     *
     * @return the allowlisted configuration values and the reasons for any drops
     */
    public SafeConfiguration safeConfiguration() {
        Map<String, String> configuration = new LinkedHashMap<>();
        Map<String, Object> omissions = new LinkedHashMap<>();
        for (String key : SAFE_CONFIG_KEYS) {
            if (isForbiddenKey(key)) {
                omissions.put("config:" + key, "forbidden_key_segment");
                continue;
            }
            String value = environment.getProperty(key);
            if (value == null) {
                // A key bound only through @ConfigurationProperties defaults is never visible to
                // the Environment, so silently skipping it would make "defaulted off" look
                // identical to "not collected". Declare it instead.
                omissions.put("config:" + key, "not_resolvable");
                continue;
            }
            if (UNSAFE_VALUE.matcher(value).find()) {
                omissions.put("config:" + key, "unsafe_value_shape");
                continue;
            }
            configuration.put(key, value);
        }
        return new SafeConfiguration(configuration, omissions);
    }

    /**
     * Returns whether a key name disqualifies it from disclosure.
     *
     * @param key the configuration key
     * @return true when the key contains a forbidden segment
     */
    public static boolean isForbiddenKey(String key) {
        String lowered = key.toLowerCase(Locale.ROOT);
        for (String forbidden : FORBIDDEN_KEY_SEGMENTS) {
            if (lowered.contains(forbidden)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The allowlisted configuration and the keys that were dropped.
     *
     * @param values    the disclosed configuration values
     * @param omissions the dropped keys, mapped to the reason they were dropped
     */
    public record SafeConfiguration(Map<String, String> values, Map<String, Object> omissions) {
    }
}
