package ooo.klae.connex.backend.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

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
 * <p>Invalid values remain the responsibility of bean validation on
 * {@link DeploymentProperties}. Forced cookie security and database transport checks are
 * intentionally outside this matrix because existing fail-closed validators already own those
 * requirements. The effective capability matrix is logged separately by
 * {@code CapabilityProfileMatrixLogger}.
 */
@Component
@RequiredArgsConstructor
public class DeploymentProfileValidator implements ApplicationRunner {

    private static final String BOOTSTRAP_ENABLED = "connex.bootstrap.enabled";
    private static final String SSO_ALLOW_PRIVATE_ISSUER_HOSTS = "connex.sso.allow-private-issuer-hosts";
    private static final String AI_ALLOW_INTERNAL_ENDPOINTS = "connex.ai.allow-internal-endpoints";
    private static final String MAIL_ALLOW_INTERNAL_HOSTS = "connex.mail.allow-internal-hosts";
    private static final List<String> CHECKED_KEYS = List.of(
        BOOTSTRAP_ENABLED,
        SSO_ALLOW_PRIVATE_ISSUER_HOSTS,
        AI_ALLOW_INTERNAL_ENDPOINTS,
        MAIL_ALLOW_INTERNAL_HOSTS
    );
    private static final Map<String, List<String>> FORBIDDEN_KEYS_BY_PROFILE = Map.of(
        DeploymentProperties.PROFILE_SAAS, CHECKED_KEYS,
        DeploymentProperties.PROFILE_SILO, List.of(),
        DeploymentProperties.PROFILE_ON_PREM, List.of()
    );
    private static final Logger log = LoggerFactory.getLogger(DeploymentProfileValidator.class);

    private final DeploymentProperties properties;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isConfigured()) {
            if (environment.acceptsProfiles(Profiles.of("dev", "test", "seeder"))) {
                log.debug("deployment profile unset (dev/test/seeder) — posture enforcement inactive");
                return;
            }
            throw new IllegalStateException(
                "CONNEX_DEPLOYMENT_PROFILE must be set to saas, silo, or on-prem outside dev/test/seeder");
        }

        String profile = properties.getProfile();
        List<String> forbiddenKeys = FORBIDDEN_KEYS_BY_PROFILE.get(profile);
        if (forbiddenKeys == null) {
            throw new IllegalStateException("Unsupported connex.deployment.profile=" + profile);
        }

        Map<String, Boolean> posture = readPosture();
        List<String> violations = forbiddenKeys.stream()
            .filter(key -> Boolean.TRUE.equals(posture.get(key)))
            .map(key -> key + "=true")
            .toList();
        if (!violations.isEmpty()) {
            throw new IllegalStateException("connex.deployment.profile=" + profile
                + " forbids: " + String.join(", ", violations));
        }

        log.info("Deployment profile {} posture enforced: {}", profile, formatPosture(posture));
    }

    private Map<String, Boolean> readPosture() {
        Map<String, Boolean> posture = new LinkedHashMap<>();
        for (String key : CHECKED_KEYS) {
            posture.put(key, environment.getProperty(key, Boolean.class, false));
        }
        return posture;
    }

    private String formatPosture(Map<String, Boolean> posture) {
        return posture.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining(", "));
    }
}
