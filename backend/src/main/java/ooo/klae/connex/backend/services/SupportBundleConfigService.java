package ooo.klae.connex.backend.services;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final Environment environment;

    /**
     * Returns the effective value of every allowlisted key that this instance has configured.
     *
     * <p>An unset key is omitted rather than emitted as null, so a reader can tell "not
     * configured" from "configured empty".
     *
     * @return the allowlisted configuration values, keyed by property name
     */
    public Map<String, String> safeConfiguration() {
        Map<String, String> configuration = new LinkedHashMap<>();
        for (String key : SAFE_CONFIG_KEYS) {
            String value = environment.getProperty(key);
            if (value != null) {
                configuration.put(key, value);
            }
        }
        return configuration;
    }
}
