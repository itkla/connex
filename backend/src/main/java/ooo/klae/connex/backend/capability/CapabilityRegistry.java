package ooo.klae.connex.backend.capability;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.config.DeploymentProperties;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.services.SsoConnectionService;
import ooo.klae.connex.backend.sso.SocialLoginClientRegistrations;

/**
 * Resolves whether instance-level product capabilities are currently available.
 */
@Service
public class CapabilityRegistry {

    private static final Map<Capability, Set<String>> FORBIDDEN_PROFILES = Map.of(
            Capability.SSO, Set.of(),
            Capability.SOCIAL_LOGIN_GOOGLE, Set.of(),
            Capability.SOCIAL_LOGIN_MICROSOFT, Set.of(),
            Capability.MANAGED_MAIL, Set.of());

    private final SsoConnectionService ssoConnectionService;
    private final SocialLoginClientRegistrations socialLoginClientRegistrations;
    private final MailProperties mailProperties;
    private final DeploymentProperties deploymentProperties;
    private final Map<Capability, Set<String>> forbiddenProfiles;

    /**
     * Creates the registry from the typed instance configuration sources.
     *
     * @param ssoConnectionService SSO capability source
     * @param socialLoginClientRegistrations social-login capability source
     * @param mailProperties mail capability source
     * @param deploymentProperties active deployment profile
     */
    @Autowired
    public CapabilityRegistry(SsoConnectionService ssoConnectionService,
            SocialLoginClientRegistrations socialLoginClientRegistrations,
            MailProperties mailProperties,
            DeploymentProperties deploymentProperties) {
        this(ssoConnectionService, socialLoginClientRegistrations, mailProperties,
                deploymentProperties, FORBIDDEN_PROFILES);
    }

    CapabilityRegistry(SsoConnectionService ssoConnectionService,
            SocialLoginClientRegistrations socialLoginClientRegistrations,
            MailProperties mailProperties,
            DeploymentProperties deploymentProperties,
            Map<Capability, Set<String>> forbiddenProfiles) {
        this.ssoConnectionService = Objects.requireNonNull(ssoConnectionService, "ssoConnectionService");
        this.socialLoginClientRegistrations = Objects.requireNonNull(
                socialLoginClientRegistrations, "socialLoginClientRegistrations");
        this.mailProperties = Objects.requireNonNull(mailProperties, "mailProperties");
        this.deploymentProperties = Objects.requireNonNull(deploymentProperties, "deploymentProperties");
        this.forbiddenProfiles = immutableForbiddenProfiles(forbiddenProfiles);
    }

    /**
     * Returns whether every availability factor permits the requested capability.
     *
     * @param capability capability to evaluate
     * @return {@code true} when the capability is available
     */
    public boolean isAvailable(Capability capability) {
        Capability requiredCapability = Objects.requireNonNull(capability, "capability");
        return profileConstraint(requiredCapability)
                && entitlement(requiredCapability)
                && rollout(requiredCapability)
                && operatorSetting(requiredCapability);
    }

    private boolean profileConstraint(Capability capability) {
        if (!deploymentProperties.isConfigured()) {
            return true;
        }
        return !forbiddenProfiles.get(capability).contains(deploymentProperties.getProfile());
    }

    /**
     * Entitlement seam for the organization capability model tracked by issue #501.
     */
    private boolean entitlement(Capability capability) {
        return true;
    }

    /**
     * Rollout seam for future gradual capability releases.
     */
    private boolean rollout(Capability capability) {
        return true;
    }

    private boolean operatorSetting(Capability capability) {
        return switch (capability) {
            case SSO -> ssoConnectionService.isInstanceEnabled();
            case SOCIAL_LOGIN_GOOGLE -> socialLoginClientRegistrations.isGoogleEnabled();
            case SOCIAL_LOGIN_MICROSOFT -> socialLoginClientRegistrations.isMicrosoftEnabled();
            case MANAGED_MAIL -> mailProperties.isManaged();
        };
    }

    private static Map<Capability, Set<String>> immutableForbiddenProfiles(
            Map<Capability, Set<String>> forbiddenProfiles) {
        Objects.requireNonNull(forbiddenProfiles, "forbiddenProfiles");
        EnumMap<Capability, Set<String>> immutableProfiles = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            Set<String> profiles = forbiddenProfiles.getOrDefault(capability, Set.of());
            immutableProfiles.put(capability, Set.copyOf(profiles));
        }
        return Collections.unmodifiableMap(immutableProfiles);
    }
}
