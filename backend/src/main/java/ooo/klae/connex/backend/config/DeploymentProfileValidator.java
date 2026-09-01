package ooo.klae.connex.backend.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityRegistry;

/**
 * Enforces deployment-profile posture constraints during startup. A deployed instance must
 * declare its edition: an unset or blank {@code connex.deployment.profile} fails startup
 * outside the dev, test, and seeder profiles, so posture enforcement can never be silently
 * inactive in production.
 *
 * <p>The seeder profile is exempt because seeder mode requires the opposite:
 * {@code SeederStartupConfigurationValidator} refuses a set {@code connex.deployment.profile},
 * and its guard already forces {@code seeder} to be the sole active profile with a non-web,
 * maintenance-mode context. Without this exemption seeder runs would be unbootable in both
 * directions.
 *
 * <p>Each profile also forbids the settings that contradict it. The SaaS profile refuses the
 * internal-access opt-ins. The on-prem profile refuses {@code connex.mail.managed}, because
 * instance-managed mail is transport Connex operates and cannot exist in an installation Connex
 * does not run. That refusal is what keeps the advertised capability matrix and actual runtime
 * behaviour consistent: {@code MailConfigResolver}, {@code WorkspaceMailConfigService}, and
 * {@code MailManagedController} read {@link ooo.klae.connex.backend.mail.MailProperties} directly
 * rather than through the capability registry, so an on-prem instance with managed mail enabled
 * would otherwise advertise the capability as unavailable while still routing mail through the
 * managed transport and locking the customer out of their own SMTP. Failing startup makes that
 * divergent state unreachable instead of merely undocumented.
 *
 * <p>Posture keys are read through the same relaxed {@link Binder} that populates the backing
 * {@code @ConfigurationProperties} beans, not exact-match {@code Environment.getProperty}. An
 * exact-match read would resolve {@code connex.mail.managed} only for its canonical spelling, so a
 * relaxed spelling the binder accepts (for instance {@code connex.mail.MANAGED}) could enable
 * managed mail while this validator saw {@code false} and admitted the on-prem boot — the exact
 * hole this refusal closes. Binding here guarantees the validator reads what the beans read.
 *
 * <p>Invalid profile values are also rejected here so they fail before the web server is created;
 * bean validation on {@link DeploymentProperties} retains the same configuration-properties
 * boundary check. Forced cookie security and database transport checks are intentionally outside
 * this matrix because existing fail-closed validators already own those requirements. The
 * effective capability matrix is logged separately by {@code CapabilityProfileMatrixLogger}.
 */
@Component
@RequiredArgsConstructor
public class DeploymentProfileValidator implements ApplicationRunner {

    private static final String BOOTSTRAP_ENABLED = "connex.bootstrap.enabled";
    private static final String SSO_ALLOW_PRIVATE_ISSUER_HOSTS = "connex.sso.allow-private-issuer-hosts";
    private static final String AI_ALLOW_INTERNAL_ENDPOINTS = "connex.ai.allow-internal-endpoints";
    private static final String MAIL_ALLOW_INTERNAL_HOSTS = "connex.mail.allow-internal-hosts";
    private static final String MAIL_MANAGED = "connex.mail.managed";
    private static final String MALWARE_SCANNING_ALLOW_DISABLED =
        "connex.malware-scanning.allow-disabled";
    private static final List<String> SAAS_FORBIDDEN_KEYS = List.of(
        BOOTSTRAP_ENABLED,
        SSO_ALLOW_PRIVATE_ISSUER_HOSTS,
        AI_ALLOW_INTERNAL_ENDPOINTS,
        MAIL_ALLOW_INTERNAL_HOSTS,
        MALWARE_SCANNING_ALLOW_DISABLED
    );
    private static final List<String> POSTURE_KEYS = List.of(
        BOOTSTRAP_ENABLED,
        SSO_ALLOW_PRIVATE_ISSUER_HOSTS,
        AI_ALLOW_INTERNAL_ENDPOINTS,
        MAIL_ALLOW_INTERNAL_HOSTS,
        MAIL_MANAGED,
        MALWARE_SCANNING_ALLOW_DISABLED
    );
    private static final Map<String, List<String>> FORBIDDEN_KEYS_BY_PROFILE = Map.of(
        DeploymentProperties.PROFILE_SAAS, SAAS_FORBIDDEN_KEYS,
        DeploymentProperties.PROFILE_SILO, List.of(MALWARE_SCANNING_ALLOW_DISABLED),
        DeploymentProperties.PROFILE_ON_PREM, List.of(MALWARE_SCANNING_ALLOW_DISABLED)
    );
    private static final Map<String, Capability> CAPABILITY_BY_POSTURE_KEY = Map.of(
        MAIL_MANAGED, Capability.MANAGED_MAIL
    );
    private static final Logger log = LoggerFactory.getLogger(DeploymentProfileValidator.class);

    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        ValidationResult result = evaluate(environment);
        if (!result.configured()) {
            log.debug("deployment profile unset (dev/test/seeder) — posture enforcement inactive");
            return;
        }
        log.info("Deployment profile {} posture enforced: {}",
            result.profile(), formatPosture(result.posture()));
    }

    static void validate(Environment environment) {
        evaluate(environment);
    }

    private static ValidationResult evaluate(Environment environment) {
        Binder binder = Binder.get(environment);
        String profile = binder.bind("connex.deployment.profile", String.class).orElse("");
        if (profile.isBlank()) {
            if (environment.acceptsProfiles(Profiles.of("dev", "test", "seeder"))) {
                return new ValidationResult(profile, Map.of());
            }
            throw new IllegalStateException(
                "CONNEX_DEPLOYMENT_PROFILE must be set to saas, silo, or on-prem outside dev/test/seeder");
        }

        List<String> forbiddenKeys = FORBIDDEN_KEYS_BY_PROFILE.get(profile);
        if (forbiddenKeys == null) {
            throw new IllegalStateException("Unsupported connex.deployment.profile=" + profile);
        }

        Map<String, Boolean> posture = readPosture(binder);
        List<String> violations = POSTURE_KEYS.stream()
            .filter(key -> isForbidden(profile, forbiddenKeys, key))
            .filter(key -> Boolean.TRUE.equals(posture.get(key)))
            .map(key -> key + "=true")
            .toList();
        if (!violations.isEmpty()) {
            throw new IllegalStateException("connex.deployment.profile=" + profile
                + " forbids: " + String.join(", ", violations));
        }

        return new ValidationResult(profile, posture);
    }

    private static boolean isForbidden(String profile, List<String> forbiddenKeys, String key) {
        if (forbiddenKeys.contains(key)) {
            return true;
        }
        Capability capability = CAPABILITY_BY_POSTURE_KEY.get(key);
        return capability != null && !CapabilityRegistry.isAllowedForProfile(capability, profile);
    }

    private static Map<String, Boolean> readPosture(Binder binder) {
        Map<String, Boolean> posture = new LinkedHashMap<>();
        for (String key : POSTURE_KEYS) {
            posture.put(key, binder.bind(key, Boolean.class).orElse(false));
        }
        return posture;
    }

    private static String formatPosture(Map<String, Boolean> posture) {
        return posture.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining(", "));
    }

    private record ValidationResult(String profile, Map<String, Boolean> posture) {

        private boolean configured() {
            return !profile.isBlank();
        }
    }
}
